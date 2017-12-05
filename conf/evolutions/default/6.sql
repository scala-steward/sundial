# --- !Ups

CREATE TYPE emr_executor_status AS ENUM ('pending', 'cancel_pending', 'running', 'completed', 'cancelled', 'failed', 'interrupted');

CREATE TABLE emr_service_state
(
  task_id     UUID PRIMARY KEY,
  job_name    VARCHAR(255),
  cluster_id  VARCHAR(128) NOT NULL,
  step_id     VARCHAR(128),
  region      VARCHAR(32),
  as_of       TIMESTAMP WITH TIME ZONE NOT NULL,
  status      emr_executor_status NOT NULL
);

# --- !Downs

DROP TABLE IF EXISTS public.emr_service_state;
DROP TYPE IF EXISTS public.emr_executor_status;