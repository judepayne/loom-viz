(ns ^{:doc "Common utilities."
      :author "Jude Payne"}
  loom-viz.util
  (:require
        [loom.graph           :as loom.graph]))


(defn predecessors-not-self
  "Returns predecessors not including self"
  [g n]
  (let [predec (loom.graph/predecessors* g n)]
    (filter #(not (= n %)) predec)))


(defn successors-not-self
  "Returns successors not including self"
  [g n]
  (let [succs (loom.graph/successors* g n)]
    (filter #(not (= n %)) succs)))


(defn root?
  "Predicate for whether the node in the graph is a root."
  [g n]
  (empty? (predecessors-not-self g n)))


(defn roots
  "Returns the roots from the graph."
  [g]
  (filter #(root? g %) (loom.graph/nodes g)))



