-- Test-only application migration that always fails: it references a table that does not exist.
CREATE TABLE fionas_broken (
    id uuid REFERENCES no_such_table (id)
);
