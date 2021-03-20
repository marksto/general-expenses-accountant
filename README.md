# General Expenses Accountant bot for Telegram

Leveraging the power of the Telegram Bot API to account for general expenses.

## Procfile variants

1. Usual:
```
;; 'heroku config:add JVM_OPTS=...' will change the value used here
web: java $JVM_OPTS -cp target/uberjar/myapp.jar clojure.main -m myapp.web
```
or, in case you've got a `-main` function which starts a server,
```
web: java $JVM_OPTS -jar target/uberjar/myapp.jar
```

2. With Leiningen:
```
;; This one counts for ':jvm-opts' key & requres 'LEIN_INCLUDE_IN_SLUG=yes'
;; If you do this, be sure to use the trampoline and with-profile tasks
web: lein with-profile production trampoline run -m myapp.web
```

## Remote REPL

1. Usual:
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
