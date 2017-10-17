;; Copyright © 2016 Dynamic Object Language Labs Inc.
;;
;; This software is licensed under the terms of the
;; Apache License, Version 2.0 which can be found in
;; the file LICENSE at the root of this distribution.

;;; Acknowledgement and Disclaimer:
;;; This material is based upon work supported by the Army Contracting
;;; and DARPA under contract No. W911NF-15-C-0005.
;;; Any opinions, findings and conclusions or recommendations expressed
;;; in this material are those of the author(s) and do necessarily reflect the
;;; views of the Army Contracting Command and DARPA.

(ns testing.pamela.inheritance
  (:require [pamela.parser :as parser]
            [pamela.unparser]
            [pamela.inheritance]
            [clojure.pprint :refer :all]
            [clojure.test :refer :all]))

(def expected-class-precedence '{a    [a a1 a11 a12 a13 a2 a3]
                                a1   [a1 a11 a12 a13]
                                a11  [a11]
                                a12  [a12]
                                a13  [a13]
                                a2   [a2]
                                a3   [a3]
                                b    [b]
                                main [main a a1 a11 a12 a13 a2 a3 b]})
(deftest test-class-precedence
  []
  (let [ir (parser/parse {:input [(clojure.java.io/file "test/pamela/inheritance-hierarchy.pamela")]})
        flat (pamela.inheritance/create-class-precedence ir)]
    ;(println "Got IR")
    ;(pprint ir)
    ;(pprint flat)
    (is (= expected-class-precedence flat) "Class precedence list should match")))

(defn parse-ir [file & [validate]]
  (parser/parse {:input [(clojure.java.io/file file)]
                 :do-not-validate (or validate true)}))

(defn test-flatten-inheritance [file]
  (let [ir (parse-ir file)
        in-ir (pamela.inheritance/flatten-inheritance ir)
        unparsed (pamela.unparser/unparse in-ir)]
    (println "original ir")
    (pprint ir)
    (println "---")
    (println "inherit-ir")
    (pprint in-ir)
    (println "---")
    (println "unparsed pamela")
    (println unparsed)
    nil))

(def test-files [["test/pamela/inherit2.pamela"
                  "test/pamela/inherit2.expected.pamela"]
                 ["test/pamela/inherit3.pamela"
                  "test/pamela/inherit3.expected.pamela"]
                 ["test/pamela/inherit4.pamela"
                  "test/pamela/inherit4.expected.pamela"]
                 ["test/pamela/inherit5.pamela"
                  "test/pamela/inherit5.expected.pamela"]
                 ["test/pamela/inherit-union.pamela"
                  "test/pamela/inherit-union.expected.pamela"]
                 ["test/pamela/inherit-precedence.pamela"
                  "test/pamela/inherit-precedence.expected.pamela"]])
(deftest flatten-inheritance
  []
  (doseq [files test-files]
    (let [file (first files)
          expected (second files)
          ir (pamela.inheritance/flatten-inheritance (parse-ir file))
          exp-ir (parse-ir expected)]
      (is (= exp-ir ir) (str "IR should match " file " " expected)))))