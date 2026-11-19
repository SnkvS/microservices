#!/bin/sh
set -eu
region="${AWS_DEFAULT_REGION:-us-east-1}"
for bucket in "${RESOURCE_STORAGE_BUCKET:-resources}" "${STAGING_STORAGE_BUCKET:-resources-staging}" "${PERMANENT_STORAGE_BUCKET:-resources-permanent}"; do
    if ! awslocal s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
        if [ "$region" = "us-east-1" ]; then
            awslocal s3api create-bucket --bucket "$bucket" --region "$region"
        else
            awslocal s3api create-bucket --bucket "$bucket" --region "$region" \
                --create-bucket-configuration "LocationConstraint=$region"
        fi
    fi
done
