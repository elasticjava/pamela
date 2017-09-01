;; Copyright © 2016 Dynamic Object Language Labs Inc.
;;
;; This software is licensed under the terms of the
;; Apache License, Version 2.0 which can be found in
;; the file LICENSE at the root of this distribution.

;; Acknowledgement and Disclaimer:
;; This material is based upon work supported by the Army Contracting
;; and DARPA under contract No. W911NF-15-C-0005.
;; Any opinions, findings and conclusions or recommendations expressed
;; in this material are those of the author(s) and do necessarily reflect the
;; views of the Army Contracting Command and DARPA.

(ns pamela.parser
  "The parser for the PAMELA language"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.pprint :as pp :refer [pprint]]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [me.raynes.fs :as fs]
            [clojure.java.io :refer [resource]]
            [camel-snake-kebab.core :as translate]
            [pamela.utils :refer [output-file display-name-string dbg-println]]
            [avenir.utils :refer [and-fn assoc-if vec-index-of concatv]]
            [instaparse.core :as insta]
            [plan-schema.utils :refer [fs-basename]]
            [plan-schema.sorting :refer [sort-mixed-map]])
  (:import [java.lang
            Long Double]))

(defn merge-keys-one
  "converts each map value v into a vector [v]"
  {:added "0.6.1"}
  ([m]
   (cond
     (map? m) (reduce-kv merge-keys-one {} m)
     :else m))
  ([m k v]
   (assoc m k (if (vector? v) v [v]))))

(defn merge-keys
  "converts all key values v into vectors [v] and coalesces values for equal keys into the respective key value"
  {:added "0.6.1"}
  ([m]
   (merge-keys-one m))
  ([m0 m1]
   (let [kvs (seq m1)]
     (loop [mk (merge-keys-one m0) kv (first kvs) more (rest kvs)]
       (if-not kv
         mk
         (let [[k v] kv
               v0 (get mk k [])
               v (conj v0 v)
               mk (assoc mk k v)]
           (recur mk (first more) (rest more)))))))
  ([m0 m1 & more]
   (apply merge-keys (merge-keys m0 m1) more)))


;; When a magic file is read in the lvar "name" is assigned a value
;;   in pamela-lvars
;; Then when the PAMELA is parsed each time an lvar is encountered
;;   If it is NOT already in pamela-lvars (or is :unset) then it is added
;;   with the default value (if specified) or :unset
;; Upon emitting the IR all the pamela-lvars are recorded
;;   under the pamela/lvars symbol
(def pamela-lvars (atom {}))

(def #^{:added "0.3.0"}
  default-bounds
  "Default bounds"
  [0 :infinity])

(def zero-bounds [0 0])

(def zero-bounds-type {:type :bounds, :value zero-bounds})

(def default-bounds-type {:type :bounds, :value default-bounds})

(def zero-delay {:type :delay
                 :temporal-constraints [zero-bounds-type]
                 :body nil})

(def default-delay {:type :delay
                    :temporal-constraints [default-bounds-type]
                    :body nil})

(def true-type {:type :literal, :value true})

(def false-type {:type :literal, :value true})

(def between-stmt-types #{:between :between-ends :between-starts})

(defn pamela-filename? [filename]
  (string/ends-with? filename ".pamela"))

(defn build-parser [& [ebnf-filename]]
  (let [ebnf-filename (or ebnf-filename "pamela.ebnf")
        ebnf (slurp (resource (str "public/" ebnf-filename)))
        whitespace (insta/parser "whitespace = #'([,\\s]+|;.*\\n)+'")
        parser (insta/parser ebnf
                 :input-format :ebnf
                 :auto-whitespace whitespace)]
    parser))

;; IR helper functions

(defn ir-boolean [v]
  (if (and (vector? v) (= (first v) :TRUE))
    true
    false))

(defn ir-integer [v]
  (Long/parseLong v))

(defn ir-float [v]
  (Double/parseDouble v))

;; field-type = ( literal | lvar-ctor | !lvar-ctor pclass-ctor |
;;                mode-expr | symbol-ref )
(defn ir-field-type [v]
  (if (map? v) ;; lvar-ctor, pclass-ctor, mode-expr, or symbol-ref
    v
    {:type :literal
     :value v}))

(defn ir-lvar-ctor [& args]
  (let [[name lvar-init] args
        magic-init (if name (get @pamela-lvars name))]
    (when (and name (nil? magic-init)) ;; not in pamela-lvars
      (swap! pamela-lvars assoc name (or lvar-init :unset)))
    (assoc-if
      {:type :lvar
       :name (or name :gensym)}
      :default lvar-init)))

(defn ir-id [id]
  {:id id})

(defn ir-plant-part [plant-part]
  {:plant-part plant-part})

(defn ir-interface [interface]
  {:interface interface})

(defn ir-pclass-ctor [name & args-opts]
  (loop [args [] options {} a (first args-opts) more (rest args-opts)]
    (if-not a
      (merge
        {:type :pclass-ctor
         :pclass name
         :args args}
        options)
      (let [args (if (map? a) args (conj args a))
            options (if (map? a) (merge options a) options)]
        (recur args options (first more) (rest more))))))

(defn ir-mode-expr [symbol-ref mode]
  {:type :mode-reference
   :names (:names symbol-ref)
   :mode mode})

(defn ir-symbol-ref [% symbols]
  {:type :symbol-ref
   :names (vec symbols)})

;; field = symbol ( <LM> field-init+ <RM> | field-type )
;; field-init = ( initial | access | observable )
(defn ir-field [field & field-inits]
  (loop [field-map {:access :private :observable false}
         field-init (first field-inits) more (rest field-inits)]
    (if-not field-init
      {field field-map}
      (let [fi (if (and (vector? field-init)
                     (= :field-init (first field-init)))
                 (second field-init)
                 field-init)
            initial (if (not (vector? fi))
                      fi
                      (if (and (vector? fi)
                            (= :initial (first fi)))
                        (second fi)))
            access (if (and (vector? fi)
                            (= :access (first fi)))
                         (second fi))
            observable (if (and (vector? fi)
                            (= :observable (first fi)))
                         (second fi))
            field-map (assoc-if
                        (if (or initial (false? initial))
                          (assoc field-map :initial initial)
                          field-map)
                        :access access
                        :observable observable)]
        (recur field-map (first more) (rest more))))))

(defn ir-mode-enum [& modes]
  (zipmap modes (repeat true-type)))

(defn ir-mode-init [mode v]
  {mode (if (= v [:TRUE]) true-type v)})

(defn ir-merge [& ms]
  (if (empty? ms)
    {}
    (apply merge ms)))

(defn ir-k-merge [k & ms]
  {k (apply merge ms)})

(defn ir-methods [& methods]
  {:methods (if (empty? methods)
              []
              (apply merge-keys methods))})

(defn ir-cond-expr [op & operands]
  (if (= op :COND-EXPR)
    (let [expr (first operands)]
      (cond
        (and (vector? expr) (= (first expr) :TRUE))
        true-type
        (and (vector? expr) (= (first expr) :FALSE))
        false-type
        :else
        expr))
    {:type op
     :args (vec operands)}))

(defn ir-map-kv [k v]
  {k v})

(defn ir-vec [& vs]
  (if (empty? vs)
    []
    (vec vs)))

(defn ir-defpclass [pclass args & options]
  {pclass
   (apply merge {:type :pclass} {:args args} options)})

(defn default-mdef [method]
  (let [cond-map {:pre true-type
                  :post true-type
                  :cost 0
                  :reward 0
                  :controllable false
                  :temporal-constraints [default-bounds-type]
                  :betweens []
                  :primitive false
                  :display-name nil
                  :body nil}
        display-name (if method (display-name-string method))]
    (assoc-if cond-map :display-name display-name)))

(defn ir-defpmethod [method & args]
  (dbg-println :trace "ir-defpmethod" method "ARGS" args)
  (loop [m (default-mdef method)]
    (loop [m m args-seen? false a (first args) more (rest args)]
      (if-not a
        {method
         (sort-mixed-map
           (assoc m :primitive (or (nil? (:body m)) (:primitive m))))}
        (let [[args-seen? m] (if (not args-seen?)
                               (if (map? a)
                                 [false (merge m a)] ;; merge in cond-map
                                 [0 (assoc m :args a)]) ;; merge in :args
                               (if (and (zero? args-seen?)
                                     (not (between-stmt-types (:type a))))
                                 [1 (assoc m :body
                                      (if (vector? a) a [a]))]
                                 [(inc args-seen?) (update-in m [:betweens]
                                                     conj a)]))]
          (dbg-println :trace "  args-seen?" args-seen? "A" a)
          ;; (println "  args-seen?" args-seen? "M"
          ;;   (with-out-str (pprint (dissoc m :body))))
          (recur m args-seen? (first more) (rest more)))))))

(defn ir-bounds-literal [lb ub]
  [lb (if (= ub [:INFINITY]) :infinity ub)])

(defn ir-opt-bounds [bounds]
  {:temporal-constraints
   [{:type :bounds
     :value bounds}]})

;; plant-fn = <LP> symbol-ref plant-opt* argval* <RP>
(defn ir-plant-fn [symbol-ref & args]
  (loop [plant-opts {} argvals [] a (first args) more (rest args)]
    (if-not a
      (merge
        {:type :plant-fn
         :names (:names symbol-ref)
         :args argvals}
        plant-opts)
      (let [[opt-or-arg v] a]
        (if (= opt-or-arg :plant-opt)
          (recur (merge plant-opts v) argvals (first more) (rest more))
          (recur plant-opts (conj argvals v) (first more) (rest more)))))))

;; NOTE: due to the refactoring of *-opts in the grammar as
;;     between-opt = ( opt-bounds | cost-le | reward-ge )
;;     fn-opt = ( between-opt | label )
;;     delay-opt = ( fn-opt | controllable )
;; AND the dispatching of those terminals in 'pamela-ir
;;     :between-opt identity
;;     ;; :fn-opt handled in ir-fn
;;     ;; :delay-opt handled in ir-fn
;; We handle the args as shown in comments below...
(defn ir-fn [f & args]
  (dbg-println :trace "IR-FN" f "ARGS" args)
  (loop [fn-opts {} body [] a (first args) more (rest args)]
    (dbg-println :trace "  FN-OPTS" fn-opts "A" a)
    (if-not a
      (merge {:type f :body (if (empty? body) nil body)} fn-opts)
      (cond
        ;; [:fn-opt OPT] where OPT is opt-bounds | cost-le | reward-ge | label
        (and (vector? a) (= :fn-opt (first a)))
        (recur (merge fn-opts (second a)) body
          (first more) (rest more))
        ;; [:delay-opt [:fn-opt OPT]]
        ;; where OPT is opt-bounds | cost-le | reward-ge | label
        (and (vector? a) (= :delay-opt (first a))
          (vector? (second a)) (= :fn-opt (first (second a))))
        (recur (merge fn-opts (-> a second second)) body
          (first more) (rest more))
        ;; [:delay-opt {:controllable true-or-false}]
        (and (vector? a) (= :delay-opt (first a)) (map? (second a)))
        (recur (merge fn-opts (second a)) body
          (first more) (rest more))
        :else
        (recur fn-opts (conj body a)
          (first more) (rest more))))))

;; by definition (at the call sites)
;; (#{:ask :assert :maintain :unless :when :whenever} f)
(defn ir-fn-cond [f cond-expr & args]
  (dbg-println :trace "IR-FN-COND" f "COND-EXPR" cond-expr "ARGS" args)
  (let [fn {:type f
            :condition cond-expr
            :body nil}
        [arg0 arg1] args
        [fn arg1] (if (and (map? arg0)
                        (= '(:temporal-constraints) (keys arg0)))
                    [(merge fn arg0) arg1] ;; opt-bounds? present
                    [fn arg0]) ;; opt-bounds? NOT present
        fn (if arg1
             (assoc fn :body [arg1])
             fn)]
    (dbg-println :trace "IR-FN-COND" f cond-expr "ARGS" args "\nFN" fn)
    fn))

(defn ir-choice [& args]
  (dbg-println :trace "IR-CHOICE ARGS" args)
  (loop [choice-opts {} body [] a (first args) more (rest args)]
    (dbg-println :trace "IR-CHOICE-OPTS" choice-opts "A" a)
    (if-not a
      (merge {:type :choice :body (if (empty? body) nil body)} choice-opts)
      (if (and (vector? a) (= :choice-opt (first a)))
        (let [choice-opt (second a)]
          ;; (log/warn "IR-CHOICE OPT" choice-opt);;
          (if (map? choice-opt)
            (recur (merge choice-opts choice-opt)
              body (first more) (rest more))
            (let [[opt val] choice-opt]
              (if (= opt :guard)
                (recur (assoc choice-opts :condition val)
                  body (first more) (rest more))
                (recur (assoc choice-opts opt val)
                  body (first more) (rest more))))))
        (recur choice-opts (conj body a) (first more) (rest more))))))

(defn ir-choose [f & args]
  ;; (log/warn "IR-CHOOSE" f (pr-str args))
  (loop [choose-opts {} body [] a (first args) more (rest args)]
    (if-not a
      (merge {:type f :body (if (empty? body) nil body)} choose-opts)
      (if (and (vector? a) (#{:choose-opt :delay-opt} (first a)))
        (if (vector? (second a))
          (if (= (first (second a)) :fn-opt)
            (let [fn-opt (second (second a))]
              (recur (merge choose-opts fn-opt) body (first more) (rest more)))
            (let [a1 (second a)
                  opt (first a1)
                  opt (if (= opt [:MIN])
                        :min
                        (if (= opt [:MAX])
                          :max
                          (if (= opt [:EXACTLY])
                            :exactly
                            :unknown-choose-opt ;; FIXME
                            )))
                  val (second a1)]
              (recur (assoc choose-opts opt val) body (first more) (rest more))))
          (recur (merge choose-opts (second a)) body (first more) (rest more)))
        (recur choose-opts (conj body a) (first more) (rest more))))))

(defn make-slack-sequence [body]
  {:type :sequence
   :body (concatv
           [default-delay]
           (interpose default-delay body)
           [default-delay])})

(defn make-slack-parallel [body]
  {:type :parallel
   :body (mapv (comp make-slack-sequence vector) body)})

(defn make-optional [body]
  {:type :choose
   :body [{:type :choice
           :body [zero-delay]}
          {:type :choice
           :body body}]})

(defn make-soft-sequence [body]
  {:type :sequence
   :body (mapv (comp make-optional vector) body)})

(defn make-soft-parallel [body]
  {:type :parallel
   :body (mapv (comp make-optional vector) body)})

(defn ir-slack-fn [f & args]
  (let [slack-fn (apply ir-fn f args)
        {:keys [type body]} slack-fn
        fn-opts (dissoc slack-fn :type :body)
        slack-fn (case type
                   :slack-sequence (make-slack-sequence body)
                   :slack-parallel (make-slack-parallel body)
                   :optional (make-optional body)
                   :soft-sequence (make-soft-sequence body)
                   :soft-parallel (make-soft-parallel body)
                   ;; default
                   {:error (log/error "invalid slack type:" type)})]
    (merge slack-fn fn-opts)))

(defn ir-between [f from to & args]
  (loop [between-opts {:type f
                       :from from
                       :to to}
         a (first args) more (rest args)]
    (if-not a
      between-opts
      (recur (merge between-opts a) (first more) (rest more)))))

(defn ir-try [& args]
  (loop [fn-opts {} body [] catch false a (first args) more (rest args)]
    (if-not a
      (merge {:type :try
              :body (if (empty? body) nil body)
              :catch (if (false? catch) nil catch)}
        fn-opts)
      (if catch
        (recur fn-opts body (conj catch a) (first more) (rest more))
        (if (and (map? a) (:temporal-constraints a))
          (recur (merge fn-opts a) body catch (first more) (rest more))
          (if (= a [:CATCH])
            (recur fn-opts body [] (first more) (rest more))
            (recur fn-opts (conj body a) catch (first more) (rest more))))))))

(defn ir-tell [cond-expr]
  {:type :tell
   :condition cond-expr})

(defn ir-inherit [& args]
  {:inherit (vec args)})

(defn ir-dotimes [times fn]
  {:type :sequence
   :body (vec (repeat times fn))})


;; If you're doing some REPL-based development, and change any of the above
;; helper functions: Don't forget to re-eval pamela-ir !!!
(def pamela-ir {
                ;; :access handled in ir-field
                :and-expr (partial ir-cond-expr :and)
                :args ir-vec
                ;; :argval handled in ir-plant-fn
                :ask (partial ir-fn-cond :ask)
                :assert (partial ir-fn-cond :assert)
                :between (partial ir-between :between)
                :between-ends (partial ir-between :between-ends)
                :between-opt identity
                :between-starts (partial ir-between :between-starts)
                :between-stmt identity
                :boolean ir-boolean
                :bounds identity
                :bounds-literal ir-bounds-literal
                :choice ir-choice
                ;; :choice-opt handled by ir-choice
                :choose (partial ir-choose :choose)
                ;; :choose-opt handled by ir-choose
                :choose-whenever (partial ir-choose :choose-whenever)
                :cond identity
                :cond-expr (partial ir-cond-expr :COND-EXPR)
                :cond-map ir-merge
                :cond-operand identity
                :controllable (partial ir-map-kv :controllable)
                :cost (partial ir-map-kv :cost)
                :cost-le (partial ir-map-kv :cost<=)
                :defpclass ir-defpclass
                :defpmethod ir-defpmethod
                :delay (partial ir-fn :delay)
                ;; :delay-opt handled in ir-fn
                :dep ir-map-kv
                :depends (partial ir-k-merge :depends)
                :display-name (partial ir-map-kv :display-name)
                :doc (partial ir-map-kv :doc)
                :dotimes ir-dotimes
                ;; :enter handled by ir-choice
                :equal-expr (partial ir-cond-expr :equal)
                :exactly ir-vec
                :field ir-field
                ;; :field-init handled in ir-field
                :field-type ir-field-type
                :fields (partial ir-k-merge :fields)
                :float ir-float
                :fn identity
                ;; :fn-opt handled in ir-fn
                ;; :guard handled in ir-choice
                :icon (partial ir-map-kv :icon)
                :id ir-id
                :implies-expr (partial ir-cond-expr :implies)
                :inherit ir-inherit
                :initial identity
                :integer ir-integer
                :interface ir-interface
                :keyword #(keyword (subs % 1))
                :label (partial ir-map-kv :label)
                ;; :leave handled by ir-choice
                :literal identity
                :lvar-ctor ir-lvar-ctor
                :lvar-init identity
                :maintain (partial ir-fn-cond :maintain)
                :max ir-vec
                :meta (partial ir-k-merge :meta)
                :meta-entry identity
                :methods ir-methods
                :min ir-vec
                :mode-enum ir-mode-enum
                :mode-expr ir-mode-expr
                :mode-init ir-mode-init
                :mode-map ir-merge
                :modes (partial ir-map-kv :modes)
                :natural ir-integer
                :not-expr (partial ir-cond-expr :not)
                :number identity
                :number-ref identity
                ;; :observable handled in ir-field
                :opt-bounds ir-opt-bounds
                :option identity
                :optional (partial ir-slack-fn :optional)
                :or-expr (partial ir-cond-expr :or)
                :pamela ir-merge
                :parallel (partial ir-fn :parallel)
                :pclass-arg-keyword identity
                :pclass-ctor ir-pclass-ctor
                :pclass-ctor-arg identity
                :pclass-ctor-option identity
                :plant-fn ir-plant-fn
                ;; :plant-opt handled in ir-plant-fn
                :plant-part ir-plant-part
                :post (partial ir-map-kv :post)
                :pre (partial ir-map-kv :pre)
                :primitive (partial ir-map-kv :primitive)
                :probability (partial ir-map-kv :probability)
                ;; reserved-keyword  only for grammer disambiguation
                ;; reserved-pclass-ctor-keyword only for grammer disambiguation
                ;; reserved-string only for grammer disambiguation
                ;; reserved-symbol only for grammer disambiguation
                :reward (partial ir-map-kv :reward)
                :reward-ge (partial ir-map-kv :reward>=)
                :safe-keyword identity
                :sequence (partial ir-fn :sequence)
                :slack-parallel (partial ir-slack-fn :slack-parallel)
                :slack-sequence (partial ir-slack-fn :slack-sequence)
                :soft-parallel (partial ir-slack-fn :soft-parallel)
                :soft-sequence (partial ir-slack-fn :soft-sequence)
                ;; stop-token only for grammer disambiguation
                :string identity
                :symbol symbol
                :symbol-ref ir-symbol-ref
                :tell ir-tell
                :trans identity
                :transition ir-map-kv
                :transitions (partial ir-k-merge :transitions)
                :trans-map ir-merge
                :try ir-try
                :unless (partial ir-fn-cond :unless)
                :version (partial ir-map-kv :version)
                :when (partial ir-fn-cond :when)
                :whenever (partial ir-fn-cond :whenever)
                ;; whitespace only for grammer disambiguation
                })

(def reference-types #{:mode-reference :field-reference :field-reference-field})

(def literal-ref-types (conj reference-types :literal))

;; (field-of this box-f)
;; {:type :field-reference
;;  :pclass this ;; or other class, or field reference
;;  :field box-f}

;; ;; :close , (mode-of this :close)
;; {:type :mode-reference
;;  :pclass this ;; or other class
;;  :mode :close}

;; ;; (whenever (= (field-of cannon-f ready) true)
;; {:type :field-reference-field
;;  :pclass this ;; or other class
;;  :field cannon-f
;;  :value ready} ;; is a mode

;; unknown (e.g. compared against the mode or field of a pclass arg)
;; NOTE: a warning will be logged
;;  {:type :literal
;;   :value :high}

;; pclass arguments
;; {:type :arg-reference
;;  :name power}

;; state variables
;; {:type :state-variable
;;  :name global-state}

(defn validate-kw-or-sym [ir state-vars pclass fields modes context kw]
  (let [[m-or-f ref] (if (symbol? kw)
                       [kw nil]
                       ;; NOTE the .: field-reference-field syntax is
                       ;; no deprecated (remove in the future).
                       (map symbol (string/split (name kw) #"\.:" 2)))
        field-ref (get fields m-or-f)
        field-pclass (if field-ref (get-in field-ref [:initial :pclass]))
        mode-kw (keyword m-or-f)
        mode-ref (get modes mode-kw)]
    ;; (log/info "VALIDATE-KW-OR-SYM context" context "m-or-f" m-or-f
    ;;   "ref" ref "field-ref" field-ref "mode-ref" mode-ref)
    (if field-ref
      (if ref
        (if (get-in ir [field-pclass :fields ref])
          ;; ref is field?
          {:type :field-reference-field
           :pclass 'this
           :field m-or-f
           :value ref} ;; is a field
          (if (get-in ir [field-pclass :modes (keyword ref)])
            ;; ref is mode?
            {:type :field-reference-mode
             :pclass 'this
             :field m-or-f
             :value ref} ;; is a mode
            {:type :error
             :msg (str "field reference invalid: " kw)}))
        {:type :field-reference
         :pclass 'this
         :field m-or-f})
      (if mode-ref
        (if ref
          {:type :error
           :msg (str "cannot reference the field of a mode: " kw)}
          {:type :mode-reference
           :pclass 'this
           :mode mode-kw})
        (do
          ;; could look in context to see if we can get type hints
          ;; from other arguments
          (log/warn "unable to determine if this keyword"
            "value is valid in pclass" pclass kw)
          {:type :literal
           :value kw})))))

;; if one arg is
;;   {:type :field-reference, :pclass this, :field :pwr}
;; and we know
;;   :initial {:type :mode-reference, :pclass pwrvals, :mode :none}
;; then we can determine
;;   (keys (get-in ir [pwrvals :modes])) ==> #{:high :none}
;; such that when we see another arg
;;   {:type :literal, :value :high}
;; THEN we can convert it
;;   :high ==> {:type :mode-reference, :pclass pwrvals, :mode :high}
;; ALSO handle :field-reference-field
(defn mode-qualification [ir dpclass fields args]
  (loop [vargs [] a (first args) more (rest args)]
    (if-not a
      vargs
      (let [{:keys [type value]} a
            a (if (= type :literal)
                (loop [va a b (first args) moar (rest args)]
                  (if-not b
                    va
                    (if (= b a)
                      (recur va (first moar) (rest moar))
                      (let [a-value value
                            {:keys [type pclass field value]} b
                            pclass (if (= pclass 'this) dpclass pclass)
                            fpclass (if (#{:field-reference
                                           :field-reference-field} type)
                                      (get-in ir [pclass :fields
                                                  field :initial :pclass]))
                            fpclass (if (and fpclass
                                          (= type :field-reference-field))
                                      (get-in ir [fpclass :fields
                                                  value :initial :pclass])
                                      fpclass)
                            values (if fpclass
                                     (set (keys (get-in ir [fpclass :modes]))))
                            va (if (and (set? values) (values a-value))
                                 {:type :mode-reference,
                                  :pclass fpclass,
                                  :mode a-value}
                                 va)]
                        (recur va (first moar) (rest moar))))))
                a)]
        (recur (conj vargs a) (first more) (rest more))))))

;; return Validated condition or {:error "message"}
(defn validate-condition [ir state-vars pclass fields modes context condition]
  ;; (dbg-println :debug  "VALIDATE-CONDITION for" pclass
  ;;   "\nfields:" (keys fields)
  ;;   "\nmodes:" (keys modes)
  ;;   "\ncontext:" context
  ;;   "\ncondition:" condition)
  (let [{:keys [type args]} condition
        pclass-args (get-in ir [pclass :args])
        [c0 c1 c2 c3] context
        method (if (= c0 :method) c1)
        method-args (if method (get-in ir [pclass :methods method c2 :args]))]
    (cond
      (and (nil? type)
        (or (keyword? condition) (symbol? condition))) ;; bare keyword/symbol
      (validate-kw-or-sym ir state-vars pclass fields modes context condition)
      (literal-ref-types type)
      condition
      (= type :equal)
      (loop [vcond {:type type} vargs [] a (first args) more (rest args)]
        (if-not a
          (assoc vcond :args (mode-qualification ir pclass fields vargs))
          (cond
            (and (map? a) (= (:type a) :field-reference)
              (get fields (:pclass a))
              (get-in (get fields (:pclass a)) [:initial :pclass])
              (get-in ir [(get-in (get fields (:pclass a)) [:initial :pclass])
                          :fields (:field a)]))
            (recur vcond
              (conj vargs {:type :field-reference-field :pclass 'this
                           :field (:pclass a) :value (:field a)})
              (first more) (rest more))
            (map? a) ;; already specified by instaparse
            (recur vcond (conj vargs a) (first more) (rest more))
            (or (keyword? a) ;; must disambiguate here
              (and (symbol? a) (get fields a))) ;; a is a field-reference
            (recur vcond
              (conj vargs (validate-kw-or-sym ir state-vars pclass fields modes
                            context a))
              (first more) (rest more))
            (symbol? a) ;; must resolve in scope here
            (recur vcond (conj vargs
                           (if (and method-args
                                 (some #(= % a) method-args))
                             {:type :method-arg-reference
                              :name a}
                             (if (some #(= % a) pclass-args)
                               {:type :arg-reference
                                :name a}
                               (do
                                 (swap! state-vars assoc a
                                   {:type :state-variable})
                                 {:type :state-variable
                                  :name a}))))
              (first more) (rest more))
            :else ;; must be a literal
            (recur vcond (conj vargs {:type :literal :value a})
              (first more) (rest more))
            )))
      :else ;; :and :or :not :implies => recurse on args
      (do
        {:type type
         :args (mapv
                 (partial validate-condition ir state-vars
                   pclass fields modes
                   (conj context type))
                 args)}))))

;; returns
;; {:error "msg"} if an arity match is NOT found
;; or {:mdef {}) method definition matching arity of caller-args
(defn validate-arity [in-pclass in-method in-mi
                      pclass methods method caller-args]
  (dbg-println :trace  "VALIDATE-ARITY" in-pclass in-method
    "TO" pclass method "WITH" caller-args)
  (let [caller-arity (count caller-args)
        caller-arg-str (if (= 1 caller-arity) " arg" " args")
        candidate-mdefs (get methods method)
        mdefs (filter #(= (count (:args %)) caller-arity) candidate-mdefs)]
    (if (= 1 (count mdefs))
      {:mdef (first mdefs)}
      (let [msg (str "Call from " in-pclass "." in-method
                  " to " pclass "." method)]
        ;; consider adding the args signature to the msg to
        ;; take advantage of in-mi and clarify which method signature
        (if (empty? candidate-mdefs)
          {:error (str msg ": method not defined")}
          (if (= 1 (count candidate-mdefs))
            (let [arity (-> candidate-mdefs first :args count)]
              {:error (str msg " has " caller-arity caller-arg-str
                        ", but expects " arity " arg"
                        (if (= 1 arity) "" "s"))})
            {:error
             (apply str msg " has " caller-arity caller-arg-str
               ", which does not match any of the available arities: "
               (interpose ", "
                 (map #(count (:args %)) candidate-mdefs)))}))))))

;; return Validated body or {:error "message"}
(defn validate-body [ir state-vars in-pclass
                     fields modes methods in-method in-mi mbody]
  (loop [vmbody []
         b (if (vector? mbody) (first mbody) mbody)
         more (if (vector? mbody) (rest mbody))]
    (if (or (:error vmbody) (not b))
      vmbody
      (let [{:keys [type field name method args condition body]} b
            [b error] (cond
                        (and (= type :plant-fn-symbol) (= name 'this))
                        (let [{:keys [error mdef]}
                              (validate-arity in-pclass in-method in-mi
                                in-pclass methods method args)]
                          (if error
                            [nil error]
                            [b nil]))
                        (and (= type :plant-fn-symbol)
                          (or
                            (some #(= name %)
                              (get-in methods [in-method in-mi :args]))
                            (some #(= name %)
                              (get-in ir [in-pclass :args]))))
                        ;; NOTE: at this point of building the IR we do not yet
                        ;; have a root-task and cannot check for arity match
                        [b nil]
                        (and (= type :plant-fn-symbol)
                          (some #(= name %) (keys fields)))
                        ;; interpret name as a field reference
                        (let [field name
                              pclass-ctor_ (get-in fields [field :initial])
                              {:keys [type pclass]} pclass-ctor_
                              {:keys [error mdef]}
                              (if (= type :pclass-ctor)
                                (validate-arity in-pclass in-method in-mi
                                  pclass (get-in ir [pclass :methods])
                                  method args)
                                ;; NOTE: in the past we just gave up on
                                ;; checking arity here for fields that
                                ;; are initialized from pclass args.
                                ;; Now we will *assume* the arity is fine
                                ;; and will catch it once we evaluate
                                ;; an actual root-task
                                ;; {:error "non :pclass-ctor check unsupported"}
                                ;; NOTE below is simply an arbitrary value
                                ;; to set mdef instead of error
                                {:mdef "arity not checked at build time"})]
                          (if error
                            [nil error]
                            [(assoc (dissoc b :name)
                               :type :plant-fn-field
                               :field field)
                             nil]))
                        (= type :plant-fn-symbol)
                        [nil (str "plant " name " used in method " in-method
                               " is not defined in the pclass " in-pclass)]
                        :else
                        [b nil])
            condition (if (and (not error) condition)
                        (validate-condition ir state-vars in-pclass fields modes
                          [:method method :body] condition))
            body (if (and (not error) (not (:error condition)) body)
                   (validate-body ir state-vars in-pclass fields modes methods
                     in-method in-mi body))
            vb (assoc-if b
                 :condition condition
                 :body body)
            vmbody (cond
                     error
                     {:error error}
                     (:error condition)
                     condition
                     (:error body)
                     body
                     :else
                     (conj vmbody vb))]
        (recur vmbody (first more) (rest more))))))

;; return Validated args or {:error "message"}
(defn validate-pclass-ctor-args [ir scope-pclass field fields pclass args]
  (loop [vargs [] a (first args) more (rest args)]
    (if (or (not a) (:error vargs))
      vargs
      (let [a (if (keyword? a)
                (if (#{:id :interface :plant-part} a)
                  a
                  {:error (str "Keyword argument to pclass constructor "
                            a " is not a pclass-ctor-option in the pclass " scope-pclass)})
                (if (symbol? a)
                  ;; is it a field or a formal arg to scope-pclass?
                  (if (or (and (get fields a)
                            (not= a field)) ;; this is another field
                        (some #(= a %) (get-in ir [scope-pclass :args])))
                    a
                    {:error (str "Symbol argument to pclass constructor "
                              a " is neither a formal argument to, "
                              "nor another field of the pclass " scope-pclass)})
                  a))
            vargs (if (and (map? a) (:error a))
                    a
                    (conj vargs a))]
        (recur vargs (first more) (rest more))))))

  ;; return Validated fields or {:error "message"}
(defn validate-fields [ir state-vars pclass fields]
  (let [field-vals (seq fields)]
    (loop [vfields {} field-val (first field-vals) more (rest field-vals)]
      (if (or (:error vfields) (not field-val))
        vfields
        (let [[field val] field-val
              {:keys [access observable initial]} val
              scope-pclass pclass
              {:keys [type pclass args name]} initial
              args (if (and (= type :pclass-ctor) args)
                     (validate-pclass-ctor-args ir scope-pclass field
                       fields pclass args)
                     args)
              val (if (and (map? args) (:error args))
                    args
                    (if (and (= type :arg-reference) (symbol? name))
                      (if (or (some #(= name %) (get-in ir [scope-pclass :args]))
                            (and (get fields name)
                              (not= name field))) ;; another field
                        val
                        {:error (str "Symbol argument to " field " field initializer "
                              name " is neither a formal argument to, "
                              "nor another field of the pclass " scope-pclass)})
                      val))
              vfields (if (and (map? val) (:error val))
                       val
                       (assoc vfields field val))]
          (recur vfields (first more) (rest more)))))))

;; return Validated modes or {:error "message"}
(defn validate-modes [ir state-vars pclass fields modes]
  (let [mode-conds (seq modes)]
    (loop [vmodes {} mode-cond (first mode-conds) more (rest mode-conds)]
      (if (or (:error vmodes) (not mode-cond))
        vmodes
        (let [[mode cond] mode-cond
              cond (validate-condition ir state-vars pclass fields modes
                     [:mode mode] cond)
              vmodes (if (:error cond)
                       cond
                       (assoc vmodes mode cond))]
          (recur vmodes (first more) (rest more)))))))

;; return Validated transitions or {:error "message"}
(defn validate-transitions [ir state-vars pclass fields modes transitions]
  (let [from-to-transitions (seq transitions)]
    (loop [vtransitions {}
           from-to-transition (first from-to-transitions)
           more (rest from-to-transitions)]
      (if (or (:error vtransitions) (not from-to-transition))
        vtransitions
        (let [[from-to transition] from-to-transition
              [from to] (map keyword (string/split (name from-to) #":"))
              ;; TODO check that from and to are valid modes
              {:keys [pre post]} transition
              pre (if pre
                    (validate-condition ir state-vars pclass fields modes
                      [:transition from-to :pre] pre))
              post (if post
                     (validate-condition ir state-vars pclass fields modes
                       [:transition from-to :post] post))
              transition (assoc-if transition
                           :pre pre
                           :post post)
              vtransitions (cond
                             (:error pre)
                             pre
                             (:error post)
                             post
                             :else
                             (assoc vtransitions from-to transition))]
          (recur vtransitions (first more) (rest more)))))))

;; return Validated methods or {:error "message"}
(defn validate-methods [ir state-vars pclass fields modes methods]
  ;; (log/warn "VALIDATE-METHODS" pclass
  ;;   ;; "METHODS" methods
  ;;   )
  (let [method-mdefss (seq methods)]
    (loop [vmethods {}
           method-mdefs (first method-mdefss)
           more (rest method-mdefss)]
      (if (or (:error vmethods) (not method-mdefs))
        vmethods
        (let [[method mdefs] method-mdefs
              vmdefs
              (loop [vmdefs []
                     mi 0
                     mdef (first mdefs)
                     moar (rest mdefs)]
                (if (or (and (map? vmdefs) (:error vmdefs)) (not mdef))
                  vmdefs
                  (let [{:keys [pre post args body]} mdef
                        pre (if pre
                              (validate-condition ir state-vars pclass fields
                                modes [:method method mi :pre] pre))
                        post (if post
                               (validate-condition ir state-vars pclass fields
                                 modes [:method method mi :post] post))
                        body (if-not (empty? body)
                               (validate-body ir state-vars pclass
                                 fields modes methods method mi body))
                        mdef (assoc-if mdef
                               :pre pre
                               :post post
                               :body body)
                        vmdefs (cond
                                 (:error pre)
                                 pre
                                 (:error post)
                                 post
                                 (:error body)
                                 body
                                 :else
                                 (conj vmdefs mdef))]
                    (recur vmdefs (inc mi) (first moar) (rest moar)))))
              vmethods (if (and (map? vmdefs) (:error vmdefs))
                         vmdefs
                         (assoc vmethods method vmdefs))]
          (recur vmethods (first more) (rest more)))))))

;; PAMELA semantic checks
;; Hoist state variables, disambiguate conditional expression operands
;; return Validated PAMELA IR or {:error "message"}
(defn validate-pamela [ir]
  ;; (log/warn "VALIDATE-PAMELA")
  (let [sym-vals (seq ir)
        state-vars (atom {})]
    (loop [vir {} sym-val (first sym-vals) more (rest sym-vals)]
      (if (or (:error vir) (not sym-val))
        (if (:error vir)
          vir
          (merge vir @state-vars))
        (let [[sym val] sym-val
              {:keys [type args fields modes transitions methods]} val
              pclass? (= type :pclass)
              fields (if (and pclass? fields)
                      (validate-fields ir state-vars sym fields))
              modes (if (and pclass? (not (:error fields)) modes)
                      (validate-modes ir state-vars sym fields modes))
              transitions (if (and pclass? transitions
                                (not (:error fields)) (not (:error modes)))
                            (validate-transitions ir state-vars
                              sym fields modes transitions))
              methods (if (and pclass? methods
                            (not (:error fields))
                            (not (:error modes))
                            (not (:error transitions)))
                            (validate-methods ir state-vars
                              sym fields modes methods))
              val (assoc-if val
                    :modes modes
                    :transitions transitions
                    :methods methods)
              vir (cond
                    (:error fields)
                    fields
                    (:error modes)
                    modes
                    (:error transitions)
                    transitions
                    (:error methods)
                    methods
                    :else
                    (assoc vir sym val))]
          (recur vir (first more) (rest more)))))))

(defn ir-magic [& args]
  args)

(def magic-ir {:boolean ir-boolean
               :bounds-literal ir-bounds-literal
               :float ir-float
               :integer ir-integer
               :keyword #(keyword (subs % 1))
               :literal identity
               :lvar-ctor ir-lvar-ctor
               :lvar-init identity
               :natural ir-integer
               :number identity
               :magic ir-magic
               :string identity
               })


(defn parse-magic [magic]
  (let [magic-parser (build-parser "magic.ebnf")
        magic-tree (insta/parses magic-parser (slurp magic))]
    (cond
      (insta/failure? magic-tree)
      (do
        (log/errorf "parse: invalid magic file: %s" magic)
        (log/errorf (with-out-str (pprint (insta/get-failure magic-tree))))
        false)
      (not= 1 (count magic-tree))
      (do
        (log/errorf "parse: grammar is ambiguous for magic file: %s" magic)
        (log/errorf (with-out-str (pprint magic-tree)))
        false)
      :else
      (let [lvars (insta/transform magic-ir (first magic-tree))]
        (loop [mir {} lvar (first lvars) more (rest lvars)]
          (if-not lvar
            mir
            (let [{:keys [name default]} lvar]
              (recur (assoc mir name (or default :unset))
                (first more) (rest more)))))))))

;; Will parse the pamela input file(s)
;; and return {:error "message"} on errors
;; else the PAMELA IR
;;   unless check-only? in which case it will return the parse
;;   tree as txt
(defn parse [options]
  ;; (log/warn "PARSE" options)
  (let [{:keys [input magic output-magic check-only?]} options
        parser (build-parser)
        mir (if magic (parse-magic magic) {})]
    (when magic
      ;; (println "Magic" magic "MIR" mir)  ;; DEBUG
      (log/debug "MAGIC input\n" (with-out-str (pprint mir))))
    (reset! pamela-lvars mir)
    (loop [ir {} input-filename (first input) more (rest input)]
      (if (or (:error ir) (not input-filename))
        (let [lvars (if check-only? [] @pamela-lvars)
              input-names (mapv fs-basename input)
              out-magic (if (pos? (count lvars))
                          (apply str
                            ";; -*- Mode: clojure; coding: utf-8  -*-\n"
                            ";; magic file corresponding to:\n"
                            ";; " input-names "\n"
                            (for [k (keys lvars)]
                              (str "(lvar \"" k "\" " (get lvars k) ")\n"))))]
          (if out-magic
            (do
              (if output-magic
                (output-file output-magic "string" out-magic))
              (assoc ir
                'pamela/lvars
                {:type :lvars
                 :lvars lvars}))
            ir))
        (let [tree (if (fs/exists? input-filename)
                     (insta/parses parser (slurp input-filename)))
              ir (cond
                   (not (fs/exists? input-filename))
                   (let [msg (str "parse: input file not found: "
                               input-filename)]
                     (log/error msg)
                     {:error msg})
                   (insta/failure? tree)
                   (let [msg (str "parse: invalid input file: "
                               input-filename)]
                     (log/error msg)
                     (log/error (with-out-str (pprint (insta/get-failure tree))))
                     {:error msg})
                   (not= 1 (count tree))
                   (let [msg (str "parse: grammar is ambiguous for input file: "
                               input-filename)]
                     (log/error msg)
                     (log/error (with-out-str (pprint tree)))
                     {:error msg})
                   :else
                   (let [tree0 (first tree)
                         ;; _ (log/warn "PRE-VALIDATE")
                         pir (if check-only?
                               {:tree tree0}
                               (validate-pamela
                                 (insta/transform pamela-ir tree0)))]
                     (if (:error pir)
                       pir
                       (merge ir pir))))]
          (recur ir (first more) (rest more)))))))
