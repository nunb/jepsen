(ns jepsen.nemesis
  (:use clojure.tools.logging)
  (:require [clojure.set        :as set]
            [jepsen.util        :as util]
            [jepsen.client      :as client]
            [jepsen.control     :as c]
            [jepsen.net         :as net]))

(def noop
  "Does nothing."
  (reify client/Client
    (setup! [this test node] this)
    (invoke! [this test op] op)
    (teardown! [this test] this)))

(defn snub-nodes!
  "Drops all packets from the given nodes."
  [test dest sources]
  (->> sources (pmap #(net/drop! (:net test) test % dest)) dorun))

(defn partition!
  "Takes a *grudge*: a map of nodes to the collection of nodes they should
  reject messages from, and makes the appropriate changes. Does not heal the
  network first, so repeated calls to partition! are cumulative right now."
  [test grudge]
  (->> grudge
       (map (fn [[node frenemies]]
              (future
                (snub-nodes! test node frenemies))))
       doall
       (map deref)
       dorun))

(defn bisect
  "Given a sequence, cuts it in half; smaller half first."
  [coll]
  (split-at (Math/floor (/ (count coll) 2)) coll))

(defn split-one
  "Split one node off from the rest"
  [coll]
  (let [loner (rand-nth coll)]
    [[loner] (remove (fn [x] (= x loner)) coll)]))

(defn complete-grudge
  "Takes a collection of components (collections of nodes), and computes a
  grudge such that no node can talk to any nodes outside its partition."
  [components]
  (let [components (map set components)
        universe   (apply set/union components)]
    (reduce (fn [grudge component]
              (reduce (fn [grudge node]
                        (assoc grudge node (set/difference universe component)))
                      grudge
                      component))
            {}
            components)))

(defn bridge
  "A grudge which cuts the network in half, but preserves a node in the middle
  which has uninterrupted bidirectional connectivity to both components."
  [nodes]
  (let [components (bisect nodes)
        bridge     (first (second components))]
    (-> components
        complete-grudge
        ; Bridge won't snub anyone
        (dissoc bridge)
        ; Nobody hates the bridge
        (->> (util/map-vals #(disj % bridge))))))

(defn partitioner
  "Responds to a :start operation by cutting network links as defined by
  (grudge nodes), and responds to :stop by healing the network."
  [grudge]
  (reify client/Client
    (setup! [this test _]
      (net/heal! (:net test) test)
      this)

    (invoke! [this test op]
      (case (:f op)
        :start (let [grudge (grudge (:nodes test))]
                 (partition! test grudge)
                 (assoc op :value (str "Cut off " (pr-str grudge))))
        :stop  (do (net/heal! (:net test) test)
                   (assoc op :value "fully connected"))))

    (teardown! [this test]
      (net/heal! (:net test) test))))

(defn partition-halves
  "Responds to a :start operation by cutting the network into two halves--first
  nodes together and in the smaller half--and a :stop operation by repairing
  the network."
  []
  (partitioner (comp complete-grudge bisect)))

(defn partition-random-halves
  "Cuts the network into randomly chosen halves."
  []
  (partitioner (comp complete-grudge bisect shuffle)))

(defn partition-random-node
  "Isolates a single node from the rest of the network."
  []
  (partitioner (comp complete-grudge split-one)))

(defn majorities-ring
  "A grudge in which every node can see a majority, but no node sees the *same*
  majority as any other."
  [nodes]
  (let [U (set nodes)
        n (count nodes)
        m (util/majority n)]
    (->> nodes
         shuffle                ; randomize
         cycle                  ; form a ring
         (partition m 1)        ; construct majorities
         (take n)               ; one per node
         (map (fn [majority]    ; invert into connections to *drop*
                [(first majority) (set/difference U (set majority))]))
         (into {}))))

(defn partition-majorities-ring
  "Every node can see a majority, but no node sees the *same* majority as any
  other. Randomly orders nodes into a ring."
  []
  (partitioner majorities-ring))

;(defn set-time!
;  "Set the local node time in POSIX seconds."
;  [t]
;  (c/su (c/exec :date "+%s" :-s (str \@ (long t)))))

(defn set-time!
  "Set the local node time skew (in seconds)"
  [t]
  (c/su (if (== 0 t)
	  (c/exec :echo :-n "" :> "/root/.faketimerc")
	  (if (> t 0)
	    (c/exec :printf "+%ds" t :> "/root/.faketimerc")
	    (c/exec :printf "%ds" t :> "/root/.faketimerc")))))


(defn clock-scrambler
  "Randomizes the system clock of all nodes within a dt-second window."
  [dt]
  (reify client/Client
    (setup! [this test _]
      this)

    (invoke! [this test op]
      (assoc op :value  (case (:f op)
                          :start (c/on-many (:nodes test)
				   (let [node_time_shift (- (rand-int (* 2 dt)) dt)]
                                     (set-time! node_time_shift)
				     node_time_shift))
                          :stop (c/on-many (:nodes test)
                                   (let []
				    (set-time! 0) 
				    "0")))))

    (teardown! [this test]
      (c/on-many (:nodes test)
                 (set-time! 0)))))

(defn node-start-stopper
  "Takes a targeting function which, given a list of nodes, returns a single
  node or collection of nodes to affect, and two functions `(start! test node)`
  invoked on nemesis start, and `(stop! test node)` invoked on nemesis stop.
  Returns a nemesis which responds to :start and :stop by running the start!
  and stop! fns on each of the given nodes. During `start!` and `stop!`, binds
  the `jepsen.control` session to the given node, so you can just call `(c/exec
  ...)`.

  Re-selects a fresh node (or nodes) for each start--if targeter returns nil,
  skips the start. The return values from the start and stop fns will become
  the :values of the returned :info operations from the nemesis, e.g.:

      {:value {:n1 [:killed \"java\"]}}"
  [targeter start! stop!]
  (let [nodes (atom nil)]
    (reify client/Client
      (setup! [this test _] this)

      (invoke! [this test op]
        (locking nodes
          (assoc op :type :info, :value
                 (case (:f op)
                   :start (if-let [ns (-> test :nodes targeter util/coll)]
                            (if (compare-and-set! nodes nil ns)
                              (c/on-many ns (start! test (keyword c/*host*)))
                              (str "nemesis already disrupting " @nodes))
                            :no-target)
                   :stop (if-let [ns @nodes]
                           (let [value (c/on-many ns (stop! test (keyword c/*host*)))]
                             (reset! nodes nil)
                             value)
                           :not-started)))))

      (teardown! [this test]))))

(defn hammer-time
  "Responds to `{:f :start}` by pausing the given process name on a given node
  or nodes using SIGSTOP, and when `{:f :stop}` arrives, resumes it with
  SIGCONT.  Picks the node(s) to pause using `(targeter list-of-nodes)`, which
  defaults to `rand-nth`. Targeter may return either a single node or a
  collection of nodes."
  ([process] (hammer-time rand-nth process))
  ([targeter process]
   (node-start-stopper targeter
                       (fn start [t n]
                         (c/su (c/exec :killall :-s "STOP" process))
                         [:paused process])
                       (fn stop [t n]
                         (c/su (c/exec :killall :-s "CONT" process))
                         [:resumed process]))))
