# Grader Workspace

This directory is the editable grader extension surface for Elastic Autograder releases.

## Layout

`runtime/`

Shared container runtime used by grader images. It reads a submission file and a manifest, executes tests, and prints normalized JSON for the backend.

`<grader-key>/manifest.json`

Assignment-specific grader manifest. The folder name should match `graderFolder` in `../config/graders.json`.

`manifestGuide.md`

Manifest format reference for Python function graders and Java/C++ stdin/stdout graders.

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

The default setup path is serial for predictable release installs. To opt into faster local rebuilds:

```bash
python scripts/setup-graders.py --parallel --build-workers 2
```

The backend jar does not package these files. Keep them on disk beside `config/graders.json` so institutions can update graders without rebuilding backend code.
