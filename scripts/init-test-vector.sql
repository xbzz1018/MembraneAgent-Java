-- Install once in the shared search path before any disposable Flyway schema exists.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
