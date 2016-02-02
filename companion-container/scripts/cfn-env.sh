#!/bin/sh

INSTANCE_ID=$(curl http://169.254.169.254/latest/meta-data/instance-id 2>/dev/null)
REGION=$(curl http://169.254.169.254/latest/dynamic/instance-identity/document 2>/dev/null|grep region|awk -F\" '{print $4}')
echo "export REGION=$REGION"

STACK_ID=$(aws ec2 describe-tags \
    --filters \
    "Name=resource-type,Values=instance" \
    "Name=resource-id,Values=$INSTANCE_ID" \
    "Name=key,Values=aws:cloudformation:stack-id" \
    --region $REGION --output text | cut -f 5)

# Unfortunately, an ASG instance may not receive the CFN tags when it is in Pending:Wait
# This is only an issue for brand-new ASG instances.
# So, we might have to get the Stack ID from the ASG that we're a member of.
if [ -z "$STACK_ID" ]; then
    ASG_NAME=$(aws autoscaling describe-auto-scaling-instances --instance-ids="$INSTANCE_ID" --region $REGION --output text | cut -f 2)
    STACK_ID=$(aws autoscaling describe-tags --filters "Name=auto-scaling-group,Values=$ASG_NAME" "Name=key,Values=aws:cloudformation:stack-id" --region $REGION --output text | cut -f 6)
fi

aws cloudformation describe-stack-resources --stack-name $STACK_ID --region $REGION --output text | cut -f 2,3 | while read LINE ; do
    RESOURCE_LOGICAL_ID=$(echo "$LINE" | cut -f 1)
    RESOURCE_PHYSICAL_ID=$(echo "$LINE" | cut -f 2)
    echo "export RESOURCE_$RESOURCE_LOGICAL_ID=$RESOURCE_PHYSICAL_ID"
done

aws cloudformation describe-stacks --stack-name $STACK_ID --region $REGION --output text | grep '^PARAMETERS' | cut -f 2,3 | while read LINE ; do
    PARAMETER_NAME=$(echo "$LINE" | cut -f 1)
    PARAMETER_VALUE=$(echo "$LINE" | cut -f 2)
    echo "export PARAMETER_$PARAMETER_NAME=\"$PARAMETER_VALUE\""
done

aws cloudformation describe-stacks --stack-name $STACK_ID --region $REGION --output text | grep '^OUTPUTS' | cut -f 2,3 | while read LINE ; do
    OUTPUT_NAME=$(echo "$LINE" | cut -f 1)
    OUTPUT_VALUE=$(echo "$LINE" | cut -f 2)
    echo "export OUTPUT_$OUTPUT_NAME=\"$OUTPUT_VALUE\""
done
