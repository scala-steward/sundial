# Sundial

What is Sundial?

Sundial is a batch job scheduler for running Dockerized batch jobs on Amazon ECS

Think of Sundial as an alternative to Chronos that uses Amazon ECS instead of Mesos and has some nice extra features.

Features:

Dependency tracking between jobs
Live viewing of Cloudwatch logs for job
Job logs collected and uploaded to S3 for browsing.
Graphite metadata.

# Getting started.

Set up service and job instances using Cloudformation template. See DEPLOY.md for details.

Submit your jobs using REST API. See description of job JSON format under JOBS.md

