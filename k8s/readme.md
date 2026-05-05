## Kubernetes Local Setup (kind + kubectl)

**Reminder:** install all dependencies first (especially **Docker Desktop**, **kind**, and **kubectl**) before running these commands.

> Also, **K8s = Kubernetes** (didnt realize that for a while xd).

---

### 1) Create the local Kubernetes cluster
Run this from the project root:

```bash
kind create cluster --config k8s\kind-config.yaml
```

The local kind config uses one control-plane node and two worker nodes so burst
tests can schedule more than one grader pod at a time.

### 2) Create the grader namespace and RBAC

```bash
kubectl apply -f k8s\grading-namespace-rbac.yaml
```

The backend still runs locally by default and uses your kubeconfig to create
grader resources in the `elastic-grading` namespace.

### 3) Verify cluster is up

```bash
kubectl get nodes
kubectl get namespace elastic-grading
kubectl cluster-info
```

### 4) Verify a test run of pods in kubernetes

```bash
kubectl run hello-test --namespace elastic-grading --image=busybox --restart=Never -- echo hello
kubectl get pods --namespace elastic-grading
kubectl logs hello-test --namespace elastic-grading
```

This should output the following: 
```bash
hello
```
