(ns csb.syntax-test
  (:require [clojure.test :refer [deftest is testing]]
            [csb.syntax :as syntax]))

(defn- tokens [text]
  (mapv (fn [{:keys [start end kind]}] [(subs text start end) kind]) (syntax/spans text)))

(deftest lexical-colours
  (is (= [["(" :delimiter] ["defn" :special] [" " :plain] ["f" :plain]
          [" " :plain] ["[" :delimiter] ["]" :delimiter] [" " :plain]
          [":demo/key" :keyword] [" " :plain] ["42" :number] [" " :plain]
          ["true" :literal] [" " :plain] ["nil" :literal] [")" :delimiter]]
         (tokens "(defn f [] :demo/key 42 true nil)")))
  (doseq [number ["-12" "+2" "1/3" "1.2e-3" "42N" "1.5M" "0xff" "2r101"]]
    (is (= [[number :number]] (tokens number))))
  (is (= [["foo42" :plain] [" " :plain] ["-" :plain] [" " :plain] ["::alias/key" :keyword]]
         (tokens "foo42 - ::alias/key"))))

(deftest strings-comments-and-characters-have-precedence
  (is (= [["\"; :key \\\"text\\\"\nnext\"" :string]]
         (tokens "\"; :key \\\"text\\\"\nnext\"")))
  (is (= [["#\"[()]+\"" :string]] (tokens "#\"[()]+\"")))
  (is (= [["; \"ignored\" :key" :comment] ["\r\n" :plain] [":next" :keyword]]
         (tokens "; \"ignored\" :key\r\n:next")))
  (doseq [character ["\\;" "\\\"" "\\)" "\\newline" "\\u03bb"]]
    (is (= [[character :character]] (tokens character))))
  (is (= [["#_" :reader] ["(" :delimiter] ["inc" :plain] [" " :plain]
          ["1" :number] [")" :delimiter]] (tokens "#_(inc 1)"))))

(deftest incomplete-input-and-unicode-preserve-every-offset
  (doseq [text ["" "(" "\\" "\"unfinished" "\"trailing\\" "#\"unfinished\\"
                "(defn café [😀]\r\n  [:ok \"λ\rtext\" ; comment\r\n])"
                "#?(:clj nil :cljs true) #/Foo ^String @x ~@xs #{1} ##Inf"]]
    (testing (pr-str text)
      (let [spans (syntax/spans text)]
        (is (= text (apply str (map (fn [{:keys [start end]}] (subs text start end)) spans))))
        (is (= (count text) (reduce + 0 (map #(- (:end %) (:start %)) spans))))
        (is (every? (fn [[a b]] (= (:end a) (:start b))) (partition 2 1 spans))))))
  (doseq [text ["\"unfinished" "\"trailing\\" "#\"unfinished\\"
                (str "\"" (apply str (repeat 20000 "λ")) "\"")]]
    (is (= [[text :string]] (tokens text)))))
