(ns ^{:doc "Processing functions for Loom graphs."
      :author "Jude Payne"}
  loom-viz.preprocessor
  (:require [loom.graph               :as loom.graph]
            [extra-loom.multigraph    :as extra-loom]
            [loom.alg-generic         :as loom.gen]
            [loom.attr                :as loom.attr]
            [loom-viz.clustered       :as clstr]
            [loom-viz.graph           :as graph]
            [clojure.set              :as set]
            [clojure.string           :as str]
            [loom-viz.util            :as util]
            [tool-belt.core           :as tb]
            [sqlpred.core             :as sql]))


;; -----------
;; Functions to manipulate the graph

(defn submap?
  "Checks whether m contains all entries in sub."
  [sub m]
  (= sub (select-keys m (keys sub))))


(defn find-node
  "Checks if part-node is part of one of the nodes in the graph. Both part-node
  and the nodes in the graph must be in map format. e.g. part-node {:id 12} and
  a node in the graph {:id 12 :name ....}. Returns the first node matched or nil."
  [g part-node]
  (reduce
   (fn [acc cur]
     (if (submap? part-node cur)
       (reduced cur)
       nil))
   (loom.graph/nodes g)))


(defn subgraph
  "Returns a sub(di)graph of g going depth first from the first occurrence of
   the (part) node n."
  [g n
   & {:keys [part-node?] :or {part-node? false}}]
  (let [node (if part-node? (find-node g n) n)]
    (apply loom.graph/digraph
           (loom.gen/pre-edge-traverse #(loom.graph/successors* g %) node))))


(defn leaves
  "Returns the leaves in the graph."
  [g]
  (filter #(graph/leaf? g %) (loom.graph/nodes g)))


(defn parents-of
  "Returns the nodes that are parents of nodes."
  [g nodes]
  (letfn [(visible-parents [g n]
            (let [prnts (loom.graph/predecessors* g n)]
              (filter #(not (graph/edge-invisible? g % n)) prnts)))]
    (dedupe (mapcat #(visible-parents g %) nodes))))


(defn remove-levels
  "Removes the n lowest levels from the graph."
  [g n]
  (let [clustered? (clstr/clustered? g)]
    (loop [grph g
           nds (leaves g)
           lvls n]
      (if (zero? lvls)
        grph
        (let [next-gen (parents-of grph nds)
              grph* (if clustered?
                      (:graph (clstr/remove-nodes grph nds))
                      (loom.graph/remove-nodes* grph nds))]
          (recur grph* next-gen (dec lvls)))))))



;; Thanks: http://hueypetersen.com/posts/2013/06/25/graph-traversal-with-clojure/
(defn eager-stateful-dfs
  "Eager depth first search that collects state as it goes.
   successors is a function of 1 arg that returns successors of node passed.
   start is the starting node.
   init is a map of initial state.
   f is a function of 3 args: current state (map), current nodes and one of its children."
  [successors start f init]
  (loop [vertices [] explored #{start} frontier [start] state init]
    (if (empty? frontier)
      state
      (let [v (peek frontier)
            neighbours (successors v)]
        (recur
          (conj vertices v)
          (into explored neighbours)
          (into (pop frontier) (remove explored neighbours))
          (reduce (fn [acc cur] (assoc acc cur (f acc v cur))) state neighbours))))))


(defn update-rank
  "Returns rank for the next (node) given state map."
  [state node next]
  (let [mx (fn [x y] (if (nil? x) y (max x y)))]
    (mx (get state next) (inc (get state node)))))



(defn ranks
  "Returns ranks for each node in g. 0-indexed."
  [g]
  (let [roots (util/roots g)
        init (zipmap roots (repeat 0))]
    ;; we need to use successors-not-self or the dfs will incorrectly increase the rank of
    ;; nodes that have edges to themselves, causing them to have a rank one higher
    ;; than other nodes and leading to an incorrect set of cluster edges.
    (reduce
     (fn [acc cur]
       (eager-stateful-dfs (partial util/successors-not-self g)
                           cur
                           update-rank
                           acc))
     init
     roots)))


(defn fmap [f m] (into (empty m) (for [[k v] m] [k (f v)])))


(defn fmap*
  "Applies f to every value in nested map."
  [f m]
  (fmap #(if (map? %)
           (fmap* f %)
           (f %))
        m))


(defn rank-info
  "Organizes ranks by k. k is usually a cluster."
  [ranks k]
  (let [r  (->> ranks
                (group-by (fn [n] (get (first n) k)))
                (into {} (map (fn [[k v]] [k (group-by second v)]))))]
    (fmap* #(map first %) r)))


(defn max-ranked-nodes
  "Returns seq of nodes at the max rank for the k. k is usually a cluster."
  [info k n]
  (let [m (into (sorted-map-by >) (get info k))
        m' (flatten (vals m))]
    (take n m')))


(defn min-ranked-nodes
  "Returns seq of nodes at the min rank for the k. k is usually a cluster."
  [info k n]
  (let [m (into (sorted-map) (get info k))
        m' (flatten (vals m))]
    (take n m')))


(def cluster-edges
  ;; meta data about cluster edges. Keep. used below
  {16 [4 4]
   12 [4 3]
   9 [3 3]
   6 [3 2]
   4 [2 2]
   2 [2 1]
   1 [1 1]})


(defn edges-between
  "Returns a set of edges between all of the min ranked nodes of clstr1
   and one of the max ranked nodes in clstr2. edges already in the graph
   are returned marked with :constraint"
  [g info ce-uppers ce-lowers clstr1 clstr2]
  (let [edges (loom.graph/edges g)
        clstr1s (clstr/cluster-descendants g (str/trim clstr1))
        clstr2s (clstr/cluster-descendants g (str/trim clstr2))
        clstr1s-mins (mapcat #(max-ranked-nodes info % ce-uppers) clstr1s)
        clstr2s-maxs (mapcat #(min-ranked-nodes info % ce-lowers) clstr2s)
        clstr-edges (for [x clstr1s-mins
                          y clstr2s-maxs]
                      [x y (if (some #{[x y]} edges) :constraint)])]
    clstr-edges))


(defn get-rank-info
  [g cluster-on]
  (let [rks (ranks g)
        ri (rank-info rks cluster-on)]
    ri))


(defn add-stack
  "Adds a stack of clusters to the graph. cluster-edge-nums is a 2-vector where
   the first is the number of nodes in the upper cluster and the second the lower."
  [g ri stack cluster-edge-nums]
  (let [edges (mapcat
               #(apply edges-between g ri
                       (first cluster-edge-nums) (second cluster-edge-nums) %)
               (partition 2 1 stack))
        ;;separate edges marked with :constraint from those that are not.
        edges' (group-by #(= :constraint (nth % 2)) edges)
        edges'-f (get edges' false)
        edges'-t (get edges' true)]
    (-> (apply loom.graph/add-edges g edges'-f) ;; don't add :constraint edges
        (loom.attr/add-attr-to-edges :style "invis" edges'-f)
        ;; for edges marked with :constraint, set the :constraint in the attrs
        (loom.attr/add-attr-to-edges :constraint true edges'-f))))


(defn add-invisible-cluster-edges
  [g opts edges]
  (let [ri (get-rank-info g (clstr/cluster-key g))
        ;; look up vector of cluster edges nums or use [2 2] as a default
        edge-nums (get cluster-edges (tb/parse-int (-> opts :num-cluster-edges)) [2 2])
        g' (reduce (fn [acc [c1 c2]]
                     (add-stack acc ri [c1 c2] edge-nums))
                   g
                   edges)]
    g'))


(defn sort-clusters-by-rank
  "Takes the ranks from an old graph and a subset of clusters and returns
   the clusters in rank order."
  [ranks clusters]
  (let [r' (into [] (vals (reduce (fn [acc [k vs]]
                                    (assoc acc k (into #{} (map first vs))))
                                  {}
                                  (group-by val ranks))))]
   (loop [old-ranks r'
           acc []
           clstrs clusters]
      (if (empty? old-ranks)
        acc
        (let [items-at (first old-ranks)
              matched (set/intersection clstrs items-at)
              unmatched (set/difference clstrs items-at)]
          (if (empty? matched)
            (recur (rest old-ranks) acc clstrs)
            (recur (rest old-ranks) (conj acc matched) unmatched)))))))


(defn rankseq->edges
  [rankseq]
  (mapcat
   (fn [[srcs dests]] (for [x srcs y dests] [x y]))
   (partition 2 1 rankseq)))


(defn filter-edge-graph
  "Filter's the graph's edge-graph to just supplied clusters."
  [g clusters]
  (let [old-edge-graph (-> g :clusters :edge-graph)
        old-ranks (ranks old-edge-graph)
        sorted-clusters (sort-clusters-by-rank old-ranks clusters)
        new-edges (rankseq->edges sorted-clusters)]
    (reduce (fn [acc [c1 c2]]
                           (-> acc
                               (clstr/add-cluster-edge c1 c2)))
            (clstr/delete-edge-graph g)
            new-edges)))


(defn filter-graph
  "Returns a filtered graph where nodes where is not a submap are filtered out."
  [g sql & {:keys [filter-edges?] :or {filter-edges? true}}]
  (let [filter-fn (complement
                   (sql/sql-pred sql
                                 :keywordize-keys? true
                                 :skip-missing? true))
        filtered-nodes (filter filter-fn
                               (loom.graph/nodes g))
        g' (if (clstr/clustered? g)
             (clstr/remove-nodes g filtered-nodes)
             (apply loom.graph/remove-nodes g filtered-nodes))]
     (if filter-edges?
       (let [edges-to-check (filter   ;; don't filter out any invis/ scaffolding edges
                             (fn [edge] (not= (loom.attr/attr g' edge :style) "invis"))
                             (loom.graph/edges g'))
             filtered-edges  (filter
                              (fn [edge]
                                (filter-fn (loom.attr/attr g' edge :meta)))
                              edges-to-check)]
        (apply loom.graph/remove-edges g' filtered-edges))
      g')))


(defn paths
  "Returns a graph with only nodes on paths between start filtering term and the end."
  [g start-subs end-subs]
  (let [start-nodes (loom.graph/nodes (filter-graph g start-subs :filter-edges? false))
        end-nodes (loom.graph/nodes (filter-graph g end-subs :filter-edges? false))
        combins (for [s start-nodes
                      e end-nodes]
                  [s e])
        paths (map
               #(apply loom.gen/bf-path (partial graph/successors g) %)
               combins)
        nds (remove nil? (into #{} (flatten paths)))
        nds-compl (set/difference (loom.graph/nodes g) nds)]
    (if (clstr/clustered? g)
      (clstr/remove-nodes g nds-compl)
      (loom.graph/remove-nodes g nds-compl))))


(defn same-ranks
  [info]
  "Filters down to just the ranks that need to be fixed."
  (into {}
        (map
         (fn [[k v]]
           (let [[_ inner] (vals v)]
             (when (> (count inner) 1)
               {k (vals v)})))
         info)))


(defn fix-ranks
  [g cluster-on]
  (let [same (same-ranks (get-rank-info g cluster-on))]
    (reduce
     (fn [acc [k v]]
       (clstr/add-attr-to-cluster acc k :fix-ranks v ))
     g
     same)))

