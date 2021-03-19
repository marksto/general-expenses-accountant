(defproject general-expenses-accountant "0.1.0-SNAPSHOT"

  :description "Leveraging the power of the Telegram Bot API to account for general expenses"
  :url "https://github.com/marksto/general-expenses-accountant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.610"]

                 [fipp "0.6.23"]

                 [compojure "1.6.2"]

                 [ring/ring-core "1.9.1"]
                 [ring/ring-devel "1.9.1"]
                 [ring/ring-jetty-adapter "1.9.1"]
                 [ring/ring-json "0.5.0"]
                 [ring/ring-defaults "0.3.2"]

                 [morse "0.4.3"]

                 [tongue "0.2.10"]

                 ;; TODO: Use these libs as well.
                 ;; - enable some more ring middleware
                 ;[metosin/compojure-api "1.1.9"]
                 ;[metosin/ring-http-response "0.8.0"]
                 ;[ring.middleware.logger "0.5.0"]
                 ;[bk/ring-gzip "0.1.1"]
                 ;; - pick one of these
                 [com.grammarly/omniconf "0.2.2"]
                 ;[coldnew/config "1.2.0"]
                 ;[wrench "0.3.3"]
                 ;; - set up logging properly
                 [com.taoensso/timbre "5.1.2"]
                 ;[org.clojure/tools.logging "0.3.1"]
                 ;[spootnik/unilog "0.7.27"]
                 ]

  :plugins [[lein-ring "0.12.5"]] ;; to run an app locally
  :ring {:handler general-expenses-accountant.handler/app}

  :profiles {:dev {:jvm-opts ["-Dclojure.spec.compile-asserts=true"]}
             :uberjar {:aot :all ;; forcing Java classes compilation
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true" ;; TODO: Test on Heroku!
                                  "-Dclojure.spec.compile-asserts=false"]
                       :main general-expenses-accountant.main
                       :omit-source true
                       :uberjar-name "gea-bot-standalone.jar"}}

  :repl-options {:init-ns general-expenses-accountant.core})
