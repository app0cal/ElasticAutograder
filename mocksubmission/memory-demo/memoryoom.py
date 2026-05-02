def stress_memory():
    # Keep every allocated block in this list so memory usage keeps growing.
    chunks = []

    # Allocate memory in visible steps: each block is about 8 MB.
    chunk_size = 8 * 1024 * 1024

    # This loop intentionally never stops. In Kubernetes, the container should
    # be stopped once it goes past the grader's memory limit.
    while True:
        chunk = bytearray(chunk_size)

        # Write to each page so the memory is actually used, not just reserved.
        for offset in range(0, chunk_size, 4096):
            chunk[offset] = 1

        # Saving the block prevents Python from freeing it before the next loop.
        chunks.append(chunk)
