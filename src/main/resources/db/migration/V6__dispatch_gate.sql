-- A short reservation mutex. Never held while waiting for the broker or a worker.
CREATE TABLE mb_dispatch_gate (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO mb_dispatch_gate(id) VALUES (1);
