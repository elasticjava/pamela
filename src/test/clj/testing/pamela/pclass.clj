;; Copyright © 2016 Dynamic Object Language Labs Inc.
;;
;; This software is licensed under the terms of the
;; Apache License, Version 2.0 which can be found in
;; the file LICENSE at the root of this distribution.

(ns testing.pamela.pclass
  (:refer-clojure :exclude [assert when sequence delay]) ;; try catch
  (:require [clojure.test :refer :all]
            [pamela.pclass :refer :all]
            [pamela.models :refer :all]))

;; NOTE: this symbol will be defined outside of the pamela.models ns
(defpclass enumvals []
  :meta {:version "0.2.0"}
  :modes [:one :two :three])

(deftest testing-pamela-pclass
  (testing "testing-pamela-pclass"
    (let [testing (lvar "testing")
          testing2 (lvar "testing2")
          ev1 (enumvals)]

      ;; test that one lvar can be bound to another
      (is (lvar? testing))
      (is (lvar? testing2))
      (set-mode! testing 123)
      (set-mode! testing2 testing)
      (is (= (mode testing) 123))
      (is (= (mode testing2) 123))

      ;; verify construction of a pclass
      (is (fn? enumvals))
      (is (= (:pamela (meta (var enumvals))) :pclass-enumeration))
      (is (map? ev1))
      (is (= (:pclass ev1) 'enumvals))
      (is (= (:pamela (meta ev1)) :pclass-instance))
      (is (= (count (keys (:modes ev1))) 3)))

    (is (= "defpclass expects a vector of args."
          (try (load-pamela-string "(defpclass bad-args :not-a-vector)")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "All defpclass args must be symbols"
          (try (load-pamela-string "(defpclass no-sym-args [:a 123])")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass :meta must be a map"
          (try (load-pamela-string "
(defpclass bad-meta []
  :meta [:not-a-map])")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= (str "defpclass meta key \":foo\" invalid, must be one of: "
             valid-meta-keys)
          (try (load-pamela-string "
(defpclass bad-meta-key []
  :meta {:foo :bar})")
               (catch Exception e (.. e getCause getMessage)))))

   (is (= "defpclass meta :version must be a string (not \"1.0\")"
          (try (load-pamela-string "
(defpclass bad-meta-ver []
  :meta {:version 1.0})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :doc must be a string (not \"[:random-stuff]\")"
          (try (load-pamela-string "
(defpclass bad-meta-doc []
  :meta {:doc [:random-stuff]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends must be a vector (not \"something\")"
          (try (load-pamela-string "
(defpclass bad-meta-depends-type []
  :meta {:depends \"something\"})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends component must be a vector (not \"something\")"
          (try (load-pamela-string "
(defpclass bad-meta-depends-item []
  :meta {:depends [\"something\"]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends component must be a vector of length 2"
          (try (load-pamela-string "
(defpclass bad-meta-depends-item2 []
  :meta {:depends [[1 2 3]]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends entry must start with a symbol (not \"1\")"
          (try (load-pamela-string "
(defpclass bad-meta-depends-item3 []
  :meta {:depends [[1 2]]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends entry must end with a string (not \"1.0\")"
          (try (load-pamela-string "
(defpclass bad-meta-depends-item4 []
  :meta {:depends [[thing 1.0]]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends upon a non-existent model: thing"
          (try (load-pamela-string "
(defpclass bad-meta-depends-missing []
  :meta {:depends [[thing \"1.0\"]]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "defpclass meta :depends upon [thing \"1.0\"] but the available version is: \"0.2.0\""
          (try (load-pamela-string "
(defpclass thing [] :meta {:version \"0.2.0\"})
(defpclass bad-meta-depends-wrong-version []
  :meta {:depends [[thing \"1.0\"]]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "Symbol b not in args [a]"
          (try (load-pamela-string "
(defpclass non-arg-field [a]
  :fields {:one b})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "Function + does not return an lvar or pclass"
          (try (load-pamela-string "
(defpclass bad-field-fn []
  :fields {:two (+ 1 2)})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "Field value [:vector-not-cool] is not an arg nor returns an lvar or pclass"
          (try (load-pamela-string "
(defpclass bad-field-val []
  :fields {:three [:vector-not-cool]})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "All pclass args must be LVars or pclasses."
          (try (load-pamela-string "
(defpclass create-wo-lvar [a])
(create-wo-lvar 123)")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "Field value :not-lvar-or-pclass is not an arg nor returns an lvar or pclass"
          (try (load-pamela-string "
(defpclass bad-field-arg []
  :fields {:four :not-lvar-or-pclass})")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "pclass: bad-transition-from has transition :FROM:TO \":foo:on\" where the :FROM is not one of: [:on :off]"
          (try (load-pamela-string "
(defpclass bad-transition-from []
  :modes [:on :off]
  :transitions {:foo:on {:pre :off :post :on}})
(bad-transition-from)")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "pclass: bad-transition-to has transition :FROM:TO \":on:*\" where the :TO is not one of: [:on :off]"
          (try (load-pamela-string "
(defpclass bad-transition-to []
  :modes [:on :off]
  :transitions {:on:* {:pre :on :post :off}})
(bad-transition-to)")
               (catch Exception e (.. e getCause getMessage)))))

    (is (= "pclass :initial mode :medium is not one of the defined modes: [:high :low]"
          (try (load-pamela-string "
(defpclass bad-initializer []
  :modes [:high :low])
(bad-initializer :initial :medium)")
               (catch Exception e (.. e getCause getMessage)))))
    ))
