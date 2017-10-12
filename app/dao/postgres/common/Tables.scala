package dao.postgres.common

object ProcessDefinitionTable {
  final val TABLE = "process_definition"
  final val COL_NAME = "process_definition_name"
  final val COL_DESCRIPTION = "description"
  final val COL_SCHEDULE = "schedule"
  final val COL_OVERLAP_ACTION = "overlap_action"
  final val COL_TEAMS = "teams"
  final val COL_DISABLED = "is_disabled"
  final val COL_CREATED_AT = "created_at"
  final val OVERLAP_WAIT = "wait"
  final val OVERLAP_TERMINATE = "terminate"
  final val OVERLAP_OVERLAP = "overlap"
  final val COL_NOTIFICATIONS = "notifications"
}

object TaskDefinitionTable {
  final val TABLE = "task_definition"
  final val COL_NAME = "task_definition_name"
  final val COL_PROC_ID = "process_id"
  final val COL_EXECUTABLE = "executable"
  final val COL_MAX_ATTEMPTS = "max_attempts"
  final val COL_MAX_EXECUTION_TIME = "max_execution_time"
  final val COL_BACKOFF_SECONDS = "backoff_seconds"
  final val COL_BACKOFF_EXPONENT = "backoff_exponent"
  final val COL_REQUIRED_DEPS = "required_dependencies"
  final val COL_OPTIONAL_DEPS = "optional_dependencies"
  final val COL_REQUIRE_EXPLICIT_SUCCESS = "require_explicit_success"
}

object TaskDefinitionTemplateTable {
  final val TABLE = "task_definition_template"
  final val COL_NAME = "task_definition_name"
  final val COL_PROC_DEF_NAME = "process_definition_name"
  final val COL_EXECUTABLE = "executable"
  final val COL_MAX_ATTEMPTS = "max_attempts"
  final val COL_MAX_EXECUTION_TIME = "max_execution_time"
  final val COL_BACKOFF_SECONDS = "backoff_seconds"
  final val COL_BACKOFF_EXPONENT = "backoff_exponent"
  final val COL_REQUIRED_DEPS = "required_dependencies"
  final val COL_OPTIONAL_DEPS = "optional_dependencies"
  final val COL_REQUIRE_EXPLICIT_SUCCESS = "require_explicit_success"
}

object ProcessTable {
  final val TABLE = "process"
  final val COL_ID = "process_id"
  final val COL_DEF_NAME = "process_definition_name"
  final val COL_STARTED = "started_at"
  final val COL_STATUS = "status"
  final val COL_ENDED_AT = "ended_at"
  final val COL_TASK_FILTER = "task_filter"
  final val STATUS_SUCCEEDED = "succeeded"
  final val STATUS_FAILED = "failed"
  final val STATUS_RUNNING = "running"
}

object TaskTable {
  final val TABLE = "task"
  final val COL_ID = "task_id"
  final val COL_PROC_DEF_NAME = "process_definition_name"
  final val COL_TASK_DEF_NAME = "task_definition_name"
  final val COL_PROCESS_ID = "process_id"
  final val COL_EXECUTABLE = "executable"
  final val COL_ATTEMPTS = "previous_attempts"
  final val COL_STARTED = "started_at"
  final val COL_STATUS = "status"
  final val COL_REASON = "reason"
  final val COL_ENDED_AT = "ended_at"
  final val STATUS_SUCCEEDED = "succeeded"
  final val STATUS_FAILED = "failed"
  final val STATUS_RUNNING = "running"
}

object TaskMetadataTable {
  final val TABLE = "task_metadata"
  final val COL_ID = "entry_id"
  final val COL_TASK_ID = "task_id"
  final val COL_WHEN = "when_"
  final val COL_KEY = "key_"
  final val COL_VALUE = "value"
}

object ReportedTaskStatusTable {
  final val TABLE = "reported_task_status"
  final val COL_TASK_ID = "task_id"
  final val COL_STATUS = "status"
  final val COL_REASON = "reason"
  final val COL_ENDED_AT = "ended_at"
  final val STATUS_SUCCEEDED = "succeeded"
  final val STATUS_FAILED = "failed"
}

object TaskTriggerRequestTable {
  final val TABLE = "task_trigger_request"
  final val COL_REQUEST_ID = "request_id"
  final val COL_PROCESS_DEF_NAME = "process_definition_name"
  final val COL_TASK_DEF_NAME = "task_definition_name"
  final val COL_REQUESTED_AT = "requested_at"
  final val COL_STARTED_PROCESS_ID = "started_process_id"
}

object ProcessTriggerRequestTable {
  final val TABLE = "process_trigger_request"
  final val COL_REQUEST_ID = "request_id"
  final val COL_PROCESS_DEF_NAME = "process_definition_name"
  final val COL_REQUESTED_AT = "requested_at"
  final val COL_STARTED_PROCESS_ID = "started_process_id"
  final val COL_TASK_FILTER = "task_filter"
}

object KillProcessRequestTable {
  final val TABLE = "kill_process_request"
  final val COL_REQUEST_ID = "request_id"
  final val COL_PROCESS_ID = "process_id"
  final val COL_REQUESTED_AT = "requested_at"
}

object ShellCommandStateTable {
  final val TABLE = "shell_command_state"
  final val COL_TASK_ID = "task_id"
  final val COL_AS_OF = "as_of"
  final val COL_STATUS = "status"
}

object ECSStateTable {
  final val TABLE = "container_service_state"
  final val COL_TASK_ID = "task_id"
  final val COL_AS_OF = "as_of"
  final val COL_STATUS = "status"
  final val COL_TASK_ARN = "task_arn"
}

object BatchStateTable {
  final val TABLE = "batch_service_state"
  final val COL_TASK_ID = "task_id"
  final val COL_AS_OF = "as_of"
  final val COL_STATUS = "status"
  final val COL_JOB_ID = "job_id"
  final val COL_JOB_NAME = "job_name"
  final val COL_LOGSTREAM_NAME = "logstream_name"
}

object GlobalLockTable {
  final val TABLE = "sundial_lock_lease"
  final val COL_LEASE_ID = "lease_id"
  final val COL_TIME_RANGE = "lease_range"
  final val COL_CLIENT_ID = "client_id"
  final val COL_CLIENT_HOSTNAME = "client_hostname"
  final val COL_CLIENT_IP_ADDR = "client_ip_address"
  final val COL_CLIENT_PROCESS = "client_process"
}