-- Disconnect all users first
SELECT pg_terminate_backend(pg_stat_activity.pid)
FROM pg_stat_activity
WHERE pg_stat_activity.datname = 'sundial_test'
  AND pid <> pg_backend_pid();

-- Only create the sundial db test user if it doesn't exist yet
DO
$body$
BEGIN
   IF NOT EXISTS (SELECT * FROM pg_catalog.pg_user WHERE  usename = 'sundial_test') THEN
      create user "sundial_test" password 'sundial_test';
   END IF;
END
$body$;

-- Drop the DB and create it again
drop database "sundial_test";

create database sundial_test owner sundial_test;

-- Make sure user has privileges on the database
grant all privileges on database sundial_test to sundial_test;

\c sundial_test

grant all privileges on all tables in schema public to "sundial_test";