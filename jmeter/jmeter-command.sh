#!/bin/bash

name=$1-$2-$3-$4
echo "testing ${name} ....."
mkdir ${name}
jmeter -n -t $1.jmx -Dthread.num=$2 -Drampup.period=$3 -Dloop.count=$4 -l ${name}.jtl -e -o ${name}/

