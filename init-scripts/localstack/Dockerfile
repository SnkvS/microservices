# Fixed Community release: local S3 needs no account or activation token.
ARG LOCALSTACK_IMAGE=localstack/localstack:4.14.0
FROM ${LOCALSTACK_IMAGE}
COPY --chmod=755 create-resource-bucket.sh /etc/localstack/init/ready.d/create-resource-bucket.sh
