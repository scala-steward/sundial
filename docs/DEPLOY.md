## Deployment Setup

There are 2 steps to deplolying Sundial.

The first step is creating the Sundial stack. 
Inside aws/ directory is a file called cfn.json . This is the Cloudformation template to be deployed using Amazon Cloudformation. 
Go to Amazon AWS Console -> Cloudformation -> Create Stack. Choose "Upload a template to Amazon S3" and point it to this cfn.json file. Fill out the parameters on the next page. You will need to know your VPC id as well as the private Subnet ids for your VPC.

**Note: When creating Cloudformation stack, it might help (debugging and troubleshooting) to make both the ELB and service subnet private.** 

The Cloudformation template will create your RDS metadata instance, your ECS cluster, an S3 bucket for logs and a service instance for the Sundial Web UI.

The last step is to deploy the Sundial service and UI. Create an ECS registry for sundial. A deploy.sh script is provided to upload deploy scripts to S3, Dockerized version of service to your Docker registry and trigger Amazon Codedeploy to pull the image and deploy it to your stack. Make sure you edit deploy.sh and application.conf and give it details of your AWS region, Docker registry etc.

# Deployment parameters

Stack name: Whatever you like. Descriptive name for the stack in Cloudformation

DBAllocated storage: Amount of storage space to give to RDS instance that stores job metadata and process logs

DBInstanceClass: A small instance like t2.small should be fine here. We don't need high performance from RDS

DBName: Fine to leave as default

DBPassword: self explanatory

DBSubnetGroup: Trickiest parameter: Need to look this up under AWS Console -> RDS -> Subnet groups. If a suitable one does not exist, create one that includes all the subnets in your VPC that match the subets your service instance will run on.

DBUSername: Fine to leave as default.

ELBScheme: Chances are you want internet-facing unless your organization has a means of routing requests to ELBs in private VPC subnet group.

ELBSubnets: Choose public subnets if you chose internet-facing ELB, private subets if you chose internal ELB

HostedZoneName: Name of hosted zone to set up DNS entry in. Look this up under Route53 in console. Use full name including periods.

BatchComputeEnvironmentName: The name of the aws batch compute environment. Okay to leave as default

BatchJobQueueName: The name of the aws batch job queue. Okay to leave as default

BatchSpotInstanceBidPercentage: The minimum percentage that a Spot Instance price must be when compared with the
      On-Demand price for that instance type before instances are launched. For example, if your
      bid percentage is 20%, then the Spot price must be below 20% of the current On-Demand price
      for that EC2 instance

BatchInstanceMaxvCpus: The maximum number of EC2 vCPUs that an environment can reach

BatchInstanceMinvCpus: The minimum number of EC2 vCPUs that an environment should maintain. Default is 0

BatchInstanceDesiredvCpus: This should be dynamically managed by AWS