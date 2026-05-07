FROM python:3.11-slim

# sets the directory for grader to run inside of 
WORKDIR /app

# ensure that the GRADER_NAME argument is provided at build time
ARG GRADER_NAME
ARG RUNTIME_PACKAGES=""
# fail the build if GRADER_NAME is not provided
RUN test -n "$GRADER_NAME"
RUN if [ -n "$RUNTIME_PACKAGES" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends $RUNTIME_PACKAGES \
      && rm -rf /var/lib/apt/lists/*; \
    fi

# copy the grader code and main.py into the container
COPY runtime/main.py /app/main.py
# copy the grader code into the container, using the GRADER_NAME argument to determine which grader to copy
COPY ${GRADER_NAME}/ /app/grader/
