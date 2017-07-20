# --- !Ups

CREATE TYPE batch_executor_status AS ENUM ('initializing', 'submitted', 'runnable', 'pending', 'starting', 'running', 'succeeded', 'failed');

CREATE TABLE batch_service_state
(
  task_id UUID PRIMARY KEY NOT NULL,
  as_of   TIMESTAMP WITH TIME ZONE NOT NULL,
  status  batch_executor_status NOT NULL,
  job_name VARCHAR(255),
  job_id UUID
);

# --- !Downs

DROP TABLE IF EXISTS public.batch_service_state;
DROP TYPE IF EXISTS public.batch_executor_status;