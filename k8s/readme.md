## Kubernetes Local Setup (kind + kubectl)

**Reminder:** install all dependencies first (especially **Docker Desktop**, **kind**, and **kubectl**) before running these commands.

> Also, **K8s = Kubernetes** (didnt realize that for a while xd).

---

### 1) Create the local Kubernetes cluster
Run this from the project root:

```bash
kind create cluster --config k8s\kind-config.yaml
```

### 2) Verify cluster is up

```bash
kubectl get nodes
kubectl cluster-info
```

### 3) Verify a test run of pods in kubernetes

```bash
kubectl run hello-test --image=busybox --restart=Never -- echo hello
kubectl get pods
kubectl logs hello-test
```

This should output the following: 
```bash
hello
```