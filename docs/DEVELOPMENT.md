## Development

### SQL Schema

Sundial requires at least Postgres 9.4, as the JSON-related types used for some tables do not exist in previous
versions.

This project uses Evolutions to manage its database schema. When modifying the schema, just add a new Evolutions
file with the appropriate ups and downs (read the
[evolutions documentation](https://www.playframework.com/documentation/2.0/Evolutions) for details).  When developing
locally, you will be prompted to apply the scripts when you attempt to access the application.  In production, as long
as the `-DapplyEvolutions.default=true` flag is set when running the application, the scripts will be applied
automatically at startup.

You will need to have a PostgreSQL database available. You can edit `conf/application.conf` with the correct
connection settings.

### Setup for Tests

To be able to run all the tests locally, you will need to setup the sundial database on your local postgres installation. For
that, run the `conf/sql/setup.sql` script as follows:

```
psql -U postgres --file /path/to/script/setup.sql
```

The connection properties for the test database come from a separate `.conf` file; `application.test.conf`. There is also a class
on the test folder - `TestConnectionPool` - which creates connections from the test database should any other test require that.

### Running in Development

The project requires that your default Java be at least Java 7.  You can create a bash function to do just that:

```
function java7() {
  export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
}
```

Running the application is just a matter of running

```
sbt run
```

Or use Docker compose which will start the application and Postgresql together