docker node update --label-add mysql=yes course-node1
docker node update --label-add redis=yes course-node2
docker node update --label-add prometheus=yes course-node4
docker node update --label-add grafana=yes course-node4
docker run -d -p 9100:9100 --name=node_exporter swr.cn-north-4.myhuaweicloud.com/oomall-javaee/prom/node_exporter:latest