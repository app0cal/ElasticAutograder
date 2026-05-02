### If you have errors when setting up the cluster + image with the script manually delete cluster or images
##### For the cluster
```bash
kind delete cluster --name elastic-autograder
```

##### For the docker images
If you want to delete both docker images, then run this
```bash
docker image rm ea-grader-fibbonaci:v1 ea-grader-twosum:v1
```
OR independently remove both docker images (this works for just deleting one in case one fails and one succeeds)
```bash
docker image rm ea-grader-fibbonaci:v1 
docker image rm ea-grader-twosum:v1 
```