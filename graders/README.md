# Grader Workspace

This directory is the editable grader extension surface for Elastic Autograder releases.

## Layout

`runtime/`

Shared container runtime used by grader images. It reads a submission file and a manifest, executes tests, and prints normalized JSON for the backend.

`<grader-key>/manifest.json`

Assignment-specific grader manifest. The folder name should match `graderFolder` in `../config/graders.json`.

`manifestGuide.md`

Manifest format reference for Python function graders, Java/C++ stdin/stdout graders, and project zip graders.

## Included Samples

- `fib`: legacy Python function grader.
- `fib-java`: single-file Java `stdio_cases` grader.
- `fib-cpp`: single-file C++ `stdio_cases` grader.
- `fib-java-project`: multi-file Java `project_cases` grader for project zip submissions.

## Add A Grader

1. Copy an existing grader folder.
2. Edit its `manifest.json`.
3. Add a matching entry to `../config/graders.json`.
4. Build and load the grader image:

```bash
python scripts/setup-graders.py --grader <grader-key>
```

The setup script uses Docker layer cache by default and builds reusable runtime base images for Python, Java, and C++ as needed. Use a clean rebuild only when you need it:

```bash
python scripts/setup-graders.py --no-cache
```

## Source Hash Labels

`scripts/setup-graders.py` adds a source-hash label to each runtime base image and grader image that it builds. The label stores a SHA-256 hash of the inputs that define the image, which lets later setup runs identify images that are already up to date and skip rebuilding or reloading them into kind.

Runtime base image hashes are created from:

- `runtime/Dockerfile.base`
- `runtime/main.py`
- the runtime language, such as Python, Java, or C++
- the runtime package list, such as `default-jdk-headless` or `g++`

Individual grader image hashes are created from:

- `runtime/Dockerfile`
- every file in that grader's folder
- the grader key, folder, and language
- the Docker image ID of the runtime base image it depends on

This means repeat setup runs are fast when files are unchanged. For example, if many graders exist and only a few grader folders changed, setup rebuilds and reloads only the changed grader images.

Important: base image changes fan out to every dependent grader. If `runtime/main.py`, `runtime/Dockerfile.base`, or a runtime package changes, the related base image gets a new identity, and every grader that uses that base image must be rebuilt.

The default setup path is serial for predictable release installs. To opt into faster local rebuilds:

```bash
python scripts/setup-graders.py --parallel --build-workers 2
```

The backend jar does not package these files. Keep them on disk beside `config/graders.json` so institutions can update graders without rebuilding backend code.
