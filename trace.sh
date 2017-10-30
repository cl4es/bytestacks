display_usage() { 
  echo -e "\nUsage:\n trace.sh output [granularity] \n" 
} 
if [ $# -le 0 ] ; then
  display_usage
  exit 1
fi
if [ ! -d FlameGraph ] ; then
  git clone https://github.com/brendangregg/FlameGraph.git
fi
granularity=25
if [ $# -eq 1 ] ; then
  granularity=$2
fi

./gradlew build && java -cp build/classes/main org.openjdk.Bytestacks $1 $granularity > $1.stacks && ls -lart $1.stacks
FlameGraph/flamegraph.pl --cp $1.stacks > $1.svg

