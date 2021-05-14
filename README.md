# General Expenses Accountant bot for Telegram

Leveraging the power of the Telegram Bot API to account for general expenses.

## Creating the app

While creating the new application, don't forget to set the EU as region.

```
heroku apps:create <app name> --region eu
```

## Getting the application name

Once app created, the metadata should be made available for inspection as config vars.

```
heroku labs:enable runtime-dyno-metadata -a <app name>
```

Then, the `HEROKU_APP_NAME` variable will become available from inside the app.

## Procfile variants

1. Usual:
```
;; 'heroku config:add JVM_OPTS=...' will change the value used here
web: java $JVM_OPTS -cp target/myapp.jar clojure.main -m myapp.web
```
or, in case you've got a `-main` function which starts a server,
```
web: java $JVM_OPTS -jar target/myapp.jar
```

2. With Leiningen:
```
;; This one counts for ':jvm-opts' key & requres 'LEIN_INCLUDE_IN_SLUG=yes'
;; If you do this, be sure to use the trampoline and with-profile tasks
web: lein with-profile production trampoline run -m myapp.web
```

## Remote REPL

1. With pre-setup [Drawbridge](https://devcenter.heroku.com/articles/debugging-clojure) handler:
```
heroku config:add REPL_USER=[...] REPL_PASSWORD=[...]
lein repl :connect http://$REPL_USER:$REPL_PASSWORD@{{name}}.herokuapp.com/repl
```

2. One-off dynos & scripts:
```
heroku run lein repl
heroku run lein run -m hello.hi
```

## Stop Heroku build

A stuck build can be cancelled manually by running the following:

```
heroku plugins:install heroku-builds
heroku builds:cancel BUILD_UUID -a APP_NAME
```

## H2-related setup

```
heroku config:set DATABASE_URL="jdbc:h2:file:/tmp/gen_exp_acc;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE"
heroku config:set DB_USER=test
heroku config:set DB_PASSWORD=test
```
