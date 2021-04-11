-- Heroku runs the SQL below to create a user and database for you.
CREATE ROLE user_name;
ALTER ROLE user_name WITH LOGIN PASSWORD 'password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE database_name OWNER user_name;
REVOKE ALL ON DATABASE database_name FROM PUBLIC;
GRANT CONNECT ON DATABASE database_name TO user_name;
GRANT ALL ON DATABASE database_name TO user_name;
