(defproject general-expenses-accountant "0.1.0-SNAPSHOT"
  ;; TODO: Migrate this project to the 'tools.deps' + clj / Makefile + Polylith architecture.

  :description "Leveraging the power of the Telegram Bot API to account for general expenses"
  :url "https://github.com/marksto/general-expenses-accountant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.async "1.3.610"]

                 [org.postgresql/postgresql "42.2.19"]
                 [hikari-cp "2.13.0"]
                 [ragtime "0.8.1"]
                 [toucan "1.15.4"]

                 [com.grammarly/omniconf "0.2.2"]
                 [com.taoensso/timbre "5.1.2"]

                 [fipp "0.6.23"]
                 [metosin/jsonista "0.2.6"]

                 [compojure "1.6.2"]

                 [hiccup "1.0.5"]

                 [ring/ring-core "1.9.1"]
                 [ring/ring-devel "1.9.1"]
                 [ring/ring-jetty-adapter "1.9.1"]
                 [ring/ring-json "0.5.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-basic-authentication "1.1.0"]

                 [morse "0.4.3"]

                 [mount "0.1.16"]

                 [nrepl/drawbridge "0.2.1"]

                 [slingshot "0.12.2"]

                 [tongue "0.2.10"]]

  :plugins [[nrepl/drawbridge "0.2.1"]]

  :aliases {"migrate-db" ["run" "-m" "general-expenses-accountant.db/lein-migrate-db"]
            "rollback-db" ["run" "-m" "general-expenses-accountant.db/lein-rollback-db"]
            "reinit-db" ["run" "-m" "general-expenses-accountant.db/lein-reinit-db"]}

  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :jvm-opts ["-Dclojure.spec.check-asserts=true"]}
             :uberjar {:aot :all ;; forcing Java classes compilation to speed up the app startup
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true" ;; TODO: Test on Heroku!
                                  "-Dclojure.spec.compile-asserts=false"]
                       :main general-expenses-accountant.main
                       :omit-source true
                       :uberjar-name "gea-bot-standalone.jar"}}

  :repl-options {:init-ns general-expenses-accountant.core})
