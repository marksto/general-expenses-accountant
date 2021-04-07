(defproject general-expenses-accountant "0.1.0-SNAPSHOT"

  :description "Leveraging the power of the Telegram Bot API to account for general expenses"
  :url "https://github.com/marksto/general-expenses-accountant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.async "1.3.610"]

                 [com.grammarly/omniconf "0.2.2"]
                 [com.taoensso/timbre "5.1.2"]

                 [fipp "0.6.23"]

                 [compojure "1.6.2"]

                 [hiccup "1.0.5"]

                 [ring/ring-core "1.9.1"]
                 [ring/ring-devel "1.9.1"]
                 [ring/ring-jetty-adapter "1.9.1"]
                 [ring/ring-json "0.5.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-basic-authentication "1.1.0"]

                 [morse "0.4.3"]

                 [nrepl/drawbridge "0.2.1"]

                 [tongue "0.2.10"]]

  :plugins [[lein-ring "0.12.5"] ;; to run an app locally with 'lein ring server(-headless)'
            [nrepl/drawbridge "0.2.1"]]
  :ring {:handler general-expenses-accountant.web/app
         :init general-expenses-accountant.main/initialize}

  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :jvm-opts ["-Dclojure.spec.check-asserts=true"]}
             :uberjar {:aot :all ;; forcing Java classes compilation to speed up the app startup
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true" ;; TODO: Test on Heroku!
                                  "-Dclojure.spec.compile-asserts=false"]
                       :main general-expenses-accountant.main
                       :omit-source true
                       :uberjar-name "gea-bot-standalone.jar"}}

  :repl-options {:init-ns general-expenses-accountant.core})
