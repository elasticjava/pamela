;; Copyright © 2016 Dynamic Object Language Labs Inc.
;;
;; This software is licensed under the terms of the
;; Apache License, Version 2.0 which can be found in
;; the file LICENSE at the root of this distribution.

(defproject pamela "0.2.2-SNAPSHOT"
  :description "Probabalistic Advanced Modeling and Execution Learning Architecture (PAMELA)"
  :url "https://github.com/dollabs/pamela"
  :scm {:url "https://github.com/dollabs/pamela.git"}
  :license {:name "Apache License, Version 2.0"
            :url "http://opensource.org/licenses/Apache-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0-RC4" :scope "provided"]
                 ;; logging
                 [com.taoensso/timbre "4.2.0"]
                 [com.fzakaria/slf4j-timbre "0.2.2"]
                 [org.clojure/tools.logging "0.3.1"
                  :exclusions [org.clojure/clojure]]
                 ;; -------
                 [org.clojure/clojurescript "1.7.189" :scope "provided"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/tools.cli "0.3.3"]
                 [riddley "0.1.12"]
                 [environ "1.0.1"]
                 [clojurewerkz/elastisch "2.2.0"]
                 [aleph "0.4.1-SNAPSHOT"]
                 [clj-time "0.11.0"]
                 [ring "1.4.0" :exclusions [clj-time]]
                 [ring/ring-defaults "0.1.5"]
                 [compojure "1.4.0" :exclusions [commons-codec]]
                 [enlive "1.1.6"]
                 [cheshire "5.5.0"]
                 [cljsjs/react-dom-server "0.14.3-0"] ;; for sablono
                 [org.omcljs/om "1.0.0-alpha28"]
                 [sablono "0.5.3"]
                 [cljs-http "0.1.39"]
                 ;; the following are to resolve dependency conflicts
                 [org.clojure/tools.reader "1.0.0-alpha3"] ;; figwheel
                 [commons-codec "1.10"] ;; compojure
                 ]

  :plugins [[lein-environ "1.0.1"]
            [codox "0.8.13" :exclusions [org.clojure/clojure]]
            [lein-cljsbuild "1.1.2" :exclusions [org.clojure/clojure]]
            [lein-figwheel "0.5.0-2"
             :exclusions [org.clojure/clojure
                          org.clojure/tools.reader
                          ring/ring-core]]]

  :hooks [leiningen.cljsbuild]

  :figwheel {:css-dirs ["resources/public/css"]
             :open-file-command "myfile-opener"}

  :source-paths ["src/main/clj"]
  :target-path "target/%s" ;; avoid AOT problems
  :clean-targets ^{:protect false}
  ["resources/public/js/compiled" :target-path :compile-path]

  ;; NOTE: need to disable aot for generating docs
  ;; https://github.com/weavejester/codox/issues/34
  :codox {:src-dir-uri "https://bitbucket.org/dollinc-pamela/pamela/src/master/code/"
          :src-linenum-anchor-prefix "cl-"
          :language :clojure ;; codox-clj
          :output-dir "doc/clj" ;; codox-clj
          :sources ["src/main/clj"] ;; codox-clj
          ;; THE following will have to wait for these to be resolved:
          ;; https://github.com/weavejester/codox/issues/90
          ;; https://github.com/weavejester/codox/issues/73
          ;; :language :clojurescript ;; codox-cljs
          ;; :output-dir "doc/cljs" ;; codox-cljs
          ;; :sources ["src/main/cljs"] ;; codox-cljs
          }

  :uberjar-name "pamela.jar"
  :uberjar-exclusions [#"META-INF/maven.*" #".*~$"]
  :main pamela.core
  ;; comment out aot when running lein doc
  :aot [pamela.core]

  :aliases {"clean-test" ^{:doc "Clean and run all tests."}
            ["do" "clean" ["test"]]
            "figwheel-test" ^{:doc "Will compile figwheel for the :test profile."}
            ["with-profile" "-dev,+test" "figwheel"]
            "prod" ^{:doc "Produce uberjar."}
            ["do" "clean" ["with-profile" "-dev,+prod" "uberjar"]]}

  :profiles
  {:dev {:env {:program-mode :dev}
         :dependencies [[org.seleniumhq.selenium/selenium-java "2.48.2"
                         :exclusions [org.eclipse.jetty/jetty-io
                                      org.eclipse.jetty/jetty-util
                                      org.apache.httpcomponents/httpcore
                                      org.apache.httpcomponents/httpclient]]
                        [clj-webdriver "0.7.2"]]
         :test-paths ["src/test/clj"]
         :cljsbuild
         {:builds
          {:app
           {:source-paths ["src/main/cljs"]
            :figwheel {:websocket-host :js-client-host
                       :on-jsload "pamela.client/figwheel-reload"}
            :compiler {:main pamela.client
                       :closure-defines {"pamela.utils.program_mode" "dev"}
                       :output-dir "resources/public/js/compiled"
                       :output-to  "resources/public/js/compiled/app.js"
                       :asset-path "js/compiled"
                       :source-map true
                       :source-map-timestamp true
                       :cache-analysis true
                       :optimizations :none
                       :pretty-print false}}}
          :test-commands
          {;; "phantomjs" ["phantomjs" "src/test/phantomjs/unit-test.js"
           ;;              "target/test/index.html"]
           "selenium" ["lein-selenium"]}}}

   :test {:env {:program-mode :test}
          :cljsbuild
          {:builds
           {:app
            {:source-paths ["src/main/cljs" "src/test/cljs"]
             :figwheel {:websocket-host :js-client-host
                       :on-jsload "pamela.client/figwheel-reload"}
             :compiler {:main testing.runner
                        :closure-defines {"pamela.utils.program_mode" "test"}
                        :output-dir    "target/test/js/compiled"
                        :output-to     "target/test/js/compiled/app.js"
                        :source-map    true
                        :asset-path    "js/compiled"
                        :cache-analysis true
                        :optimizations :none
                        :pretty-print  false}}}}}

   :prod {:env {:program-mode :prod}
          :cljsbuild
          {:builds
           {:app
            {:source-paths ["src/main/cljs"]
             :compiler {:main pamela.client
                        :closure-defines {"pamela.utils.program_mode" "prod"}
                        :output-dir "resources/public/js/compiled"
                        :output-to  "resources/public/js/compiled/app.js"
                        :asset-path "js/compiled"
                        :cache-analysis false
                        :optimizations :advanced
                        :pretty-print false}}}}}}

   :uberjar {:omit-source true
             ;; :aot :all
             })
