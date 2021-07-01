# General Expenses Accountant bot for Telegram

Leveraging the power of the Telegram Bot API to account for general expenses.

## Creating the app

While creating the new application, don't forget to set the EU as region.

```
heroku apps:create <APP_NAME> --region eu
```

## Getting the application name

Once app created, the metadata should be made available for inspection as config vars.

```
heroku labs:enable runtime-dyno-metadata -a <APP_NAME>
```

Then, the `HEROKU_APP_NAME` variable will become available from inside the app.

## Procfile variants

1. Usual:
```
;; 'heroku -a <APP_NAME> config:add JVM_OPTS=...' will change the value used here
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
heroku -a <APP_NAME> config:add REPL_USER=[...] REPL_PASSWORD=[...]
lein repl :connect http://$REPL_USER:$REPL_PASSWORD@<APP_NAME>.herokuapp.com/repl
```

2. One-off dynos & scripts:
```
heroku run lein repl -a <APP_NAME>
heroku run lein run -m hello.hi
```

## Stop Heroku build

A stuck build can be cancelled manually by running the following:

```
heroku plugins:install heroku-builds
heroku builds:cancel <BUILD_UUID> -a <APP_NAME>
```

## Provisioning the Database

The Heroku Postgres is used as the app's primary (and only) database.

```
heroku addons:create heroku-postgresql:hobby-dev -a <APP_NAME>
```

The database can take up to 5 minutes to become available. You can track its status with the heroku `pg:wait` command,
which blocks until your database is ready to use.

As part of the provisioning process, a `DATABASE_URL` config var is added to your app’s configuration. This contains
the URL your app uses to access the primary database.

[//]: # (TODO: Quote the "Sharing Heroku Postgres between applications" section.)

## DB Data Import & Export

1. `pg:pull` can be used to pull remote data from a Heroku Postgres database to a database on your local machine.
The command looks like this:

```
heroku pg:pull DATABASE_URL mylocaldb -a <APP_NAME>
```

This command creates a new local database named `mylocaldb` and then pulls data from the database at `DATABASE_URL` 
from the specified app.

To prevent accidental data overwrites and loss, the local database _must not already exist_.

If providing a Postgres user or password for your local DB is necessary, use the appropriate env vars like so:

```
PGUSER=postgres PGPASSWORD=password ...
```

2. `pg:push` pushes data from a local database into a remote Heroku Postgres database.
The command looks like this:

```
heroku pg:push mylocaldb DATABASE_URL -a <APP_NAME>
```

This command takes the local database `mylocaldb` and pushes it to the database at `DATABASE_URL` on the specified app.

To prevent accidental data overwrites and loss, the remote database _must be empty_.

Usage of the `PGUSER` and `PGPASSWORD` for your local database is also supported, just like for the `pg:pull` command.
