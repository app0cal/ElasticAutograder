FROM python:3.11-slim

# sets the directory for grader to run inside of 
WORKDIR /app

# ensure that the GRADER_NAME argument is provided at build time
ARG GRADER_NAME
# fail the build if GRADER_NAME is not provided
RUN test -n "$GRADER_NAME"

# copy the grader code and main.py into the container
COPY runtime/main.py /app/main.py
# copy the grader code into the container, using the GRADER_NAME argument to determine which grader to copy
COPY ${GRADER_NAME}/ /app/grader/