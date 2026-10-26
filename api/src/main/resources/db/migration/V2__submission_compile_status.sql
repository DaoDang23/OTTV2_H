ALTER TABLE submissions DROP CONSTRAINT submissions_status_check;
ALTER TABLE submissions ADD CONSTRAINT submissions_status_check
    CHECK (status IN ('PENDING','COMPILING','COMPILED','COMPILE_ERROR','SMOKE_TESTING','ACTIVE','REJECTED','RETIRED'));
