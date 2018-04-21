package com.bf.sparkproject.spark;

import akka.routing.Broadcast;
import com.alibaba.fastjson.JSONObject;
import com.bf.sparkproject.MockData;
import com.bf.sparkproject.conf.ConfigurationManager;
import com.bf.sparkproject.constant.Constants;
import com.bf.sparkproject.dao.ITaskDAO;
import com.bf.sparkproject.dao.impl.DAOFactory;
import com.bf.sparkproject.domain.Task;
import com.bf.sparkproject.util.*;
import jodd.util.collection.IntArrayList;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntList;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.util.*;

/**
 * Created by wanglei on 2018/4/9.
 * 用户访问Session分析Spark作业
 * <p>
 * 接收用户创建的分析任务，用户可能指定的条件如下：
 * 1、时间范围：起始日期-结束日期
 * 2、性别：男或女
 * 3、年龄范围
 * 4、职业：多选
 * 5、城市：多选
 * 6、搜索次：多个搜索词，只要某个session中的任何一个action搜索过指定的关键词，那么session就符合条件
 * 7、点击品类：多个品类，只要某个session中的任何一个action点击过某个品类，那么session就符合条件
 * <p>
 * 我们的spark作业如何接受用户创建的任务？
 * J2EE平台在接收用户创建任务的请求之后，会将任务信息插入MySQl的task表中，任务参数以JSON格式封装在task_param字段中
 * <p>
 * 接着J2EE平台会执行我们的spark-submit shell脚本，并将taskid作为参数传递给spark-submit shell 脚本
 * spark-submit shell脚本,在执行时，是可以接收参数的，并且会将接收的参数，传递给Spark作业的main函数
 * 参数就封装在main函数的args数组中
 * <p>
 * 这是Spark本身提供的特性
 */

public class UserVisitSessionAnalyzeSpark {
    public static void main(String[] args) {
        SparkConf conf = new SparkConf()
                .setAppName(Constants.SPARK_APP_NAME_SESSION)
                .set("spark.default.parallelism", "100")
                .set("spark.storage.memoryFraction", "0.5")
                .set("spark.shuffle.file.buffer", "64")
                .set("spark.shuffle.memoryFraction", "0.3")
                .set("spark.reducer.maxSizeInFlight", "24")
                .set("spark.shuffle.io.maxRetries", "60")
                .set("spark.shuffle.io.retryWait", "60")
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//                .registerKryoClasses(new Class[]{CategorySo})
                .setMaster("local");
        SparkUtils.setMaster(conf);

        JavaSparkContext sc = new JavaSparkContext(conf);

        //sc.sc()  从JavaSparkContext中取出它对应的那个SparkContext
        SQLContext sqlContext = getSQLContext(sc.sc());


        //生成模拟测试数据
        SparkUtils.mockData(sc, sqlContext);

        //创建需要使用的DAO组件
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();


        //首先得查询出来指定的任务,并获取任务的查询参数
        long taskId = ParamUtils.getTaskIdFromArgs(args);
        Task task = taskDAO.findById(taskId);
        if (task == null) {
            //这里做一次判断，如果没有taskid，为了程序不报错，直接return返回
            System.out.println(new Date() + ": cannot find this task with id [" + taskId + "].");
            return;
        }

        JSONObject taskParam = JSONObject.parseObject(task.getTask_param());

        //如果要进行session粒度的数据聚合
        //首先要从user_visit_action表中，查询出来指定日期范围内的行为数据
        /**
         * actionRDD,就是一个公共RDD
         * 第一，要用actionRDD，获取到一个公共的sessionid为key的PairRDD
         * 第二,actionRDD，用在了session聚合环节里面
         *
         * sessionid为key的PairRDD，是确定了在后面要多次使用的
         * 1、与通过筛选的sessionid进行join，获取通过筛选的session的明细数据
         * 2、将这个RDD，直接传入aggregateBySession方法，进行sessionid为key的RDD
         *
         * 重构完以后，actionRDD，就只在最开始，使用一次，用来生成sessionid为key的RDD
         */


        //如果要根据用户在创建任务时指定的参数，来进行数据过滤和筛选
        JavaRDD<Row> actionRDD = getActionRDDByDateRange(sqlContext, taskParam);

        //这里从最原始的actionRDD进行了一次转换，生成了以sessionid为key，action为value的键值对RDD
        JavaPairRDD<String, Row> sessionid2actionRDD = getSession2ActionRDD(actionRDD);

        /**
         * 持久化，很简单，就是对RDD调用persist()方法，并传入一个持久化级别
         *
         * 如果是persist(StorageLevel.MEMORY ONLY()),纯内存，无序列化，那么就可以用cache()方法来替代
         * StorageLevel.MEMORY_ONLY_SER()，第二选择
         * StorageLevel.MEMORY_AND_DISK()，第三选择
         * StorageLevel.MEMORY_AND_DISK_SER()，第四选择
         * StorageLevel.DISK_ONLY()，第五选择
         *
         * 如果内存充足，要使用双副本高可靠机制
         * 选择后缀带_2的策略
         * StorageLevel.MEMORY_ONLY_2()
         */
        sessionid2actionRDD = sessionid2actionRDD.persist(StorageLevel.MEMORY_ONLY());
        sessionid2actionRDD.checkpoint();

        //首先，可以将行为数据，按照session_id进行groupByKey分组
        //此时的数据的粒度就是session粒度了，然后呢，可以将session粒度的数据
        //与用户信息数据，进行join
        //然后就可以获取到session粒度的数据，同时呢，数据里面还包含了session对应的user的信息
        //到这里为止，获取的数据是<sessionid,(sessionid,searchKeywords,clickCategoryIds,age,professional,city,sex)>
        JavaPairRDD<String, String> sessionid2AggrInfoRDD = aggregateBySession(sc, sqlContext, sessionid2actionRDD);

        //接着，就要针对session粒度的聚合数据，按照使用者指定的筛选参数进行数据过滤
        //相当于我们自己编写的算子，是要访问外面的任务参数对象的
        //所以，大家记得我们之前说的，匿名内部类(算子函数)，访问外部对象，是要给外部对象使用final修饰的

        //重构，同时进行过滤和统计
        Accumulator<String> sessionAggrStatAccumulator = sc.accumulator("", new SessionAggrStatAccumulator());

        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = filterSessionAndAggrStat(sessionid2AggrInfoRDD, taskParam, sessionAggrStatAccumulator);
        filteredSessionid2AggrInfoRDD = filteredSessionid2AggrInfoRDD.persist(StorageLevel.MEMORY_ONLY());

        //生成公共的RDD：通过筛选条件的session的访问明细数据

        /**
         *重构：sessionid2detailRDD 就是代表了通过筛选的session对应的访问明细数据
         */
        JavaPairRDD<String, Row> sessionid2detailRDD = getSessionid2detailRDD(filteredSessionid2AggrInfoRDD, sessionid2actionRDD);
        sessionid2detailRDD = sessionid2detailRDD.persist(StorageLevel.MEMORY_ONLY());

        /**
         * 对于Accumulator这种分布式累加计算的变量的使用，有一个重要说明
         *
         * 从Accumulator中，获取数据，插入数据库的时候，一定要，一定要，是在某一个action操作以后再进行
         *
         * 如果没有action的话，那么整个程序根本不会运行
         *
         * 是不是在calculateAndPersisitAggrStat方法之后，运行一个action操作，比如count，take
         * 不对！
         *
         * 必须把能够触发job执行的操作，放在最终写入MySQL方法之前
         *
         * 计算出来的结果，在J2EE中，是怎么显示的，是用两张柱状图显示
         */

        randomExtraSession(sc, task.getTask_id(), filteredSessionid2AggrInfoRDD, sessionid2detailRDD);


        //关闭上下文
        sc.close();
    }

    /**
     * 随机抽取session
     *
     * @param sc
     * @param task_id
     * @param sessionid2AggrInfoRDD
     * @param sessionid2actionRDD
     */
    private static void randomExtraSession(
            JavaSparkContext sc,
            long task_id,
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            JavaPairRDD<String, Row> sessionid2actionRDD) {
        /**
         * 第一步、计算出每天每小时的session数量
         */
        //获取<yyyy-MM-dd_HH,aggrInfo>格式的RDD
        JavaPairRDD<String, String> time2sessionidRDD = sessionid2AggrInfoRDD.mapToPair(new PairFunction<Tuple2<String, String>, String, String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Tuple2<String, String> call(Tuple2<String, String> tuple) throws Exception {
                String aggrInfo = tuple._2;
                String startTime = StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_START_TIME);
                String dateHour = DateUtils.getDateHour(startTime);
                return new Tuple2<String, String>(dateHour, aggrInfo);
            }
        });

        /**
         * 思考一下：这里我们不要着急写大量的代码，做项目的时候，一定要用脑子多思考
         *
         * 每天每小时的session数量，然后计算出每天每小时的session抽取索引，遍历每天每小时session
         * 首先抽取出的session的聚合数据，写入session_random_extract表
         * 所以第一个RDD的value，应该是session聚合数据
         */

        //得到每天每小时的session数量

        /**
         * 每天每小时的session数量的计算
         * 是有可能出现数据倾斜的吧，这个是没有疑问的
         * 比如说大部分小时，一般访问量也就10万，但是，中午12点的时候，高峰期，一个小时1000万
         * 这个时候，就会发生数据倾斜
         *
         * 我们就用这个countByKey操作，给大家演示第三种和第四种方案
         *
         */
        Map<String, Object> countMap = time2sessionidRDD.countByKey();

        /**
         * 第二步，使用按时间比例随机抽取算法，计算出每天每小时要抽取session的索引
         */

        //将<yyyy-MM-dd_HH,count>格式的map，转换成<yyyy-MM-dd,<HH,count>>的格式
        Map<String, Map<String, Long>> dateHourCountMap = new HashMap<String, Map<String, Long>>();
        for (Map.Entry<String, Object> countEntry : countMap.entrySet()) {
            String dateHour = countEntry.getKey();
            String date = dateHour.split("_")[0];
            String hour = dateHour.split("_")[1];

            long count = Long.valueOf(String.valueOf(countEntry.getValue()));

            Map<String, Long> hourCountMap = dateHourCountMap.get(date);
            if (hourCountMap == null) {
                hourCountMap = new HashMap<String, Long>();
                dateHourCountMap.put(date, hourCountMap);
            }
            hourCountMap.put(hour, count);
        }

        //开始实现我们的按时间比例随机抽取算法
        //总共要抽取100个session，先按照天数，进行平分
        int extractNumberPerDay = 100 / dateHourCountMap.size();

        /**
         * session随机抽取功能
         *
         * 用到了一个比较大的变量，随机抽取索引map
         * 之前是直接在算子里面使用了这个map，那么根据我们刚才讲的这个原理，每个task都会拷贝一份map副本
         * 还是比较小号内存和网络传输性能的
         *
         * 将map做成广播变量
         */

        Map<String, Map<String, List<Integer>>> dateHourExtractMap = new HashMap<String, Map<String, List<Integer>>>();
        Random random = new Random();

        for (Map.Entry<String, Map<String, Long>> dateHourCountEntry : dateHourCountMap.entrySet()) {
            String date = dateHourCountEntry.getKey();
            Map<String, Long> hourCountMap = dateHourCountEntry.getValue();

            //计算出这一天的session总数
            long sessionCount = 0L;
            for (long hourCount : hourCountMap.values()) {
                sessionCount += hourCount;
            }

            Map<String, List<Integer>> hourExtractMap = dateHourExtractMap.get(date);
            if (hourExtractMap == null) {
                hourExtractMap = new HashMap<String, List<Integer>>();
                dateHourExtractMap.put(date, hourExtractMap);
            }

            //遍历每个小时
            for (Map.Entry<String, Long> hourCountEntry : hourCountMap.entrySet()) {
                String hour = hourCountEntry.getKey();
                long count = hourCountEntry.getValue();

                //计算每个小时的session数量，占据当天总session数量的比例，直接乘以每天要抽取的数量
                //就可以计算出，当前小时需要抽取的session数量
                int hourExtractNumber = (int) (((double) count / (double) sessionCount) * extractNumberPerDay);
                if (hourExtractNumber > count) {
                    hourExtractNumber = (int) count;
                }

                //先获取当前小时的存放随机数的list
                List<Integer> extractIndexList = hourExtractMap.get(hour);
                if (extractIndexList == null) {
                    extractIndexList = new ArrayList<Integer>();
                    hourExtractMap.put(hour, extractIndexList);
                }

                //生成上面计算出来的数量的随机数
                for (int i = 0; i < hourExtractNumber; i++) {
                    int extractIndex = random.nextInt((int) count);
                    while (extractIndexList.contains(extractIndex)) {
                        extractIndex = random.nextInt((int) count);
                    }
                    extractIndexList.add(extractIndex);
                }
            }
        }
        /**
         * fastutil的使用，很简单，比如List<Integer>的List，对应到fastutil，就是IntList
         */

        Map<String, Map<String, IntArrayList>> fastutilDateHourExtractMap = new HashMap<String, Map<String, IntArrayList>>();

        for (Map.Entry<String, Map<String, List<Integer>>> dateHourExtractEntry : dateHourExtractMap.entrySet()) {
            String date = dateHourExtractEntry.getKey();
            Map<String, List<Integer>> hourExtractMap = dateHourExtractEntry.getValue();
            Map<String, IntArrayList> fastutilHourExtractMap = new HashMap<String, IntArrayList>();

            for (Map.Entry<String, List<Integer>> hourExtractEntry : hourExtractMap.entrySet()) {
                String hour = hourExtractEntry.getKey();
                List<Integer> extractList = hourExtractEntry.getValue();

                IntArrayList fastutilExtractList = new IntArrayList();

                for (int i = 0; i < extractList.size(); i++) {
                    fastutilExtractList.add(extractList.get(i));
                }
                fastutilHourExtractMap.put(hour, fastutilExtractList);
            }
            fastutilDateHourExtractMap.put(date, fastutilHourExtractMap);
        }

        /**
         * 广播变量，很简单
         * 其实就是SparkContext的broadcast()方法，传入你要广播的变量，即可
         */
        final org.apache.spark.broadcast.Broadcast<Map<String, Map<String, IntArrayList>>> dateHourExtractMapBroadcast =
                sc.broadcast(fastutilDateHourExtractMap);

        /**
         * 第三步：遍历每天每小时的session，然后根据随机索引进行抽取
         */
        //执行groupByKey算子，得到<dateHour,(session aggrInfo)>
        JavaPairRDD<String, Iterable<String>> time2sessionsRDD = time2sessionidRDD.groupByKey();

        //我们用flatMap算子，遍历所有的<dateHour,(session aggrInfo)>格式的数据
        //然后呢，会遍历每天每小时的session
        //如果发现某个session恰巧在我们指定的这天这小时的随机抽取索引上
        //那么抽取该session，直接写入MySQL的random_extract_session表
        //将抽取出来的session id返回回来，去join他们的访问行为明细数据，写入session表
        JavaPairRDD<String, String> extractSessionRDD = time2sessionsRDD.flatMapToPair(
                new PairFlatMapFunction<Tuple2<String, Iterable<String>>, String, String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Iterable<Tuple2<String, String>> call(Tuple2<String, Iterable<String>> tuple) throws Exception {
                        List<Tuple2<String, String>> extractSessionids = new ArrayList<Tuple2<String, String>>();
                        String dateHour = tuple._1;
                        String date = dateHour.split("_")[0];
                        String hour = dateHour.split("_")[1];

                        return null;
                    }
                }
        );

    }

    /**
     * 获取通过筛选条件的session的访问明细数据RDD
     *
     * @param sessionid2aggrInfoRDD
     * @param sessionid2actionRDD
     */
    private static JavaPairRDD<String, Row> getSessionid2detailRDD(
            JavaPairRDD<String, String> sessionid2aggrInfoRDD,
            JavaPairRDD<String, Row> sessionid2actionRDD) {
        JavaPairRDD<String, Row> sessionid2detailRDD = sessionid2aggrInfoRDD.join(sessionid2actionRDD).mapToPair(
                new PairFunction<Tuple2<String, Tuple2<String, Row>>, String, Row>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<String, Row> call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
                        return new Tuple2<String, Row>(tuple._1, tuple._2._2);
                    }
                });
        return sessionid2detailRDD;
    }

    /**
     * 过滤session数据，并进行聚合统计
     *
     * @param sessionid2AggrInfoRDD
     * @param taskParam
     * @param sessionAggrStatAccumulator
     */
    private static JavaPairRDD<String, String> filterSessionAndAggrStat(
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            JSONObject taskParam,
            final Accumulator<String> sessionAggrStatAccumulator) {
        //为了使用我们后面的ValieUtils，所以，首先将所有的筛选参数拼接成一个连接串
        //此外，这里其实大家不要觉得是多次一举
        //其实我们是给后面的性能优化埋下了一个伏笔
        String startAge = ParamUtils.getParam(taskParam, Constants.PARAM_START_DATE);
        String endAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
        String professionals = ParamUtils.getParam(taskParam, Constants.PARAM_PROFESSIONALS);
        String cities = ParamUtils.getParam(taskParam, Constants.PARAM_CITIES);
        String sex = ParamUtils.getParam(taskParam, Constants.PARAM_SEX);
        String keywords = ParamUtils.getParam(taskParam, Constants.PARAM_KEYWORDS);
        String categoryIds = ParamUtils.getParam(taskParam, Constants.PARAM_CATEGORY_IDS);

        String _parameter = (startAge != null ? Constants.PARAM_START_AGE + "=" + startAge + "|" : "")
                + (endAge != null ? Constants.PARAM_END_AGE + "=" + endAge + "|" : "")
                + (professionals != null ? Constants.PARAM_PROFESSIONALS + "=" + professionals + "|" : "")
                + (cities != null ? Constants.PARAM_CITIES + "=" + sex + "|" : "")
                + (sex != null ? Constants.PARAM_SEX + "=" + sex + "|" : "")
                + (keywords != null ? Constants.PARAM_KEYWORDS + "=" + keywords + "|" : "")
                + (categoryIds != null ? Constants.PARAM_CATEGORY_IDS + "=" + categoryIds : "");
        if (_parameter.endsWith("\\|")) {
            _parameter = _parameter.substring(0, _parameter.length() - 1);
        }

        final String parameter = _parameter;

        //根据筛选参数进行过滤
        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = sessionid2AggrInfoRDD.filter(
                new Function<Tuple2<String, String>, Boolean>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Boolean call(Tuple2<String, String> tuple) throws Exception {
                        //首先，从tuple中，获取聚合数据
                        String aggrInfo = tuple._2;


                        //接着，依次按照筛选条件进行过滤
                        //按照年龄范围进行过滤（startAge,endAge）
                        if (!ValidUtils.between(aggrInfo, Constants.FIELD_AGE, parameter, Constants.PARAM_START_AGE, Constants.PARAM_END_AGE)) {
                            return false;
                        }

                        //按照职业范围进行过滤（professionals）
                        //互联网，IT，软件
                        //互联网
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_PROFESSIONAL, parameter, Constants.PARAM_PROFESSIONALS)) {
                            return false;
                        }

                        //按照城市范围进行过滤(cities)
                        //北上广深
                        //成都
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_CITY, parameter, Constants.PARAM_CITIES)) {
                            return false;
                        }

                        //按照性别进行过滤
                        //男/女
                        //男，女
                        if (!ValidUtils.equal(aggrInfo, Constants.FIELD_SEX, parameter, Constants.PARAM_SEX)) {
                            return false;
                        }

                        //按照搜索词进行过滤
                        //我们的session可能搜索了  火锅，蛋糕，烧烤
                        //我们的筛选条件可能是  火锅，串串香，iphone手机
                        //那么，in这个校验方法，主要判定session搜索的词中，有任何一个，与筛选条件中
                        //任何一个搜索词相当，即通过
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_SEARCH_KEYWORDS, parameter, Constants.PARAM_KEYWORDS)) {
                            return false;
                        }

                        //按照点击品类id进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_CLICK_CATEGORY_IDS, parameter, Constants.PARAM_CATEGORY_IDS)) {
                            return false;
                        }

                        //如果经过了之前的多个过滤条件之后，程序能走到这里
                        //那么就说明，该session是通过了用户指定的筛选条件的，也就是需要保留的session'
                        //那么就要对session的访问时长和步长，进行统计，根据session对应的范围
                        //进行相应的累加计数

                        //主要走到这一步，那么就是需要计数的session
                        sessionAggrStatAccumulator.add(Constants.SESSION_COUNT);

                        //计算出session的访问时长和访问步长的范围，并进行相应的累加
                        long visitLength = Long.valueOf(StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_VISIT_LENGTH));
                        long stepLength = Long.valueOf(StringUtils.getFieldFromConcatString(aggrInfo, "\\|", Constants.FIELD_STEP_LENGTH));

                        calculateVisitLength(visitLength);

                        return null;
                    }

                    /**
                     * 计算访问时长范围
                     * @param visitLength
                     */
                    private void calculateVisitLength(long visitLength) {
                        if (visitLength >= 1 && visitLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1s_3s);
                        } else if (visitLength >= 4 && visitLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_4s_6s);
                        } else if (visitLength >= 7 && visitLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_7s_9s);
                        } else if (visitLength >= 10 && visitLength <= 30) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10s_30s);
                        } else if (visitLength > 30 && visitLength <= 60) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30s_60s);
                        } else if (visitLength > 60 && visitLength <= 180) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1m_3m);
                        } else if (visitLength > 180 && visitLength <= 600) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_3m_10m);
                        } else if (visitLength > 600 && visitLength <= 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10m_30m);
                        } else if (visitLength > 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30m);
                        }
                    }

                    /**
                     * 计算访问步长范围
                     * @param stepLength
                     */
                    private void calculateStepLength(long stepLength) {
                        if (stepLength >= 1 && stepLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_1_3);
                        } else if (stepLength >= 4 && stepLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_4_6);
                        } else if (stepLength >= 7 && stepLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_7_9);
                        } else if (stepLength >= 10 && stepLength <= 30) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_10_30);
                        } else if (stepLength > 30 && stepLength <= 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_30_60);
                        } else if (stepLength > 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_60);
                        }
                    }


                });

        return filteredSessionid2AggrInfoRDD;
    }


    private static JavaPairRDD<String, Row> getSession2ActionRDD(JavaRDD<Row> actionRDD) {
        return actionRDD.mapToPair(new PairFunction<Row, String, Row>() {
            @Override
            public Tuple2<String, Row> call(Row row) throws Exception {

                return new Tuple2<String, Row>(row.getString(2), row);
            }
        });

    }


    /**
     * 获取SQLContext
     * 如果是在本地测试环境的话，那么就生成SQLContext对象
     * 如果是在生产环境运行的话，那么就生成HiveContext对象
     *
     * @param sc
     * @return
     */
    private static SQLContext getSQLContext(SparkContext sc) {
        boolean local = ConfigurationManager.getBoolean(Constants.SPARK_APP_NAME_SESSION);
        if (local) {
            return new SQLContext(sc);
        } else {
            return new HiveContext(sc);
        }

    }

    /**
     * 生成模拟数据(只有本地模式，才会生成模拟数据)
     *
     * @param sc
     * @param sqlContext
     */
    private static void mockData(JavaSparkContext sc, SQLContext sqlContext) {
        Boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        if (local) {
            MockData.mock(sc, sqlContext);
        }

    }

    /**
     * 获取指定日期范围内的用户访问行为数据
     *
     * @param sqlContext
     * @param taskParam
     * @return
     */
    private static JavaRDD<Row> getActionRDDByDateRange(SQLContext sqlContext, JSONObject taskParam) {
        String startDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE);
        String endDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE);

        String sql = "select * from user_visit_action where date >='" + startDate + "' and date <= '" + endDate + "' ";

        DataFrame df = sqlContext.sql(sql);

        /**
         * 这里就很有可能发生上面说的问题
         * 比如说，Spark SQL默认就给第一个stage设置了20个task，但是根据你的数据量以及算法的复杂度
         * 实际上，你需要1000个task去并行执行
         *
         * 所以说，在这里，就可以对Spark SQL刚刚查询出来的RDD执行repartition重分区操作
         */
        //return actionDF.javaRDD().repartition(1000)
        return df.javaRDD();

    }

    /**
     * 对行为数据按session粒度进行聚合
     *
     * @return
     */
    private static JavaPairRDD<String, String> aggregateBySession(
            JavaSparkContext sc,
            SQLContext sqlContext,
            JavaPairRDD<String, Row> sessionid2actionRDD) {
        //对行为数据按session粒度进行分组
        JavaPairRDD<String, Iterable<Row>> sessionid2ActionsRDD = sessionid2actionRDD.groupByKey();

        //对每一个session分组进行聚合，将session中所有的搜索词和点击品类都聚合起来
        JavaPairRDD<Long, String> userid2PartAggrInfoRDD = sessionid2ActionsRDD.mapToPair(new PairFunction<Tuple2<String, Iterable<Row>>, Long, String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> tuple) throws Exception {
                String sessionid = tuple._1;
                Iterator<Row> iterator = tuple._2.iterator();
                StringBuffer searchKeywordsBuffer = new StringBuffer("");
                StringBuffer clickCategoryIdsBuffer = new StringBuffer("");

                Long userid = null;

                //session的起始时间和结束时间
                Date startTime = null;
                Date endTime = null;

                //sessionde的访问步长
                int stepLength = 0;

                //遍历session所有的访问行为
                while (iterator.hasNext()) {
                    //提取每个访问行为的搜索词字段和点击品类字段
                    Row row = iterator.next();
                    if (userid == null) {
                        userid = row.getLong(1);
                    }
                    String searchKeyword = row.getString(5);
                    Long clickCategoryId = row.getLong(6);

                    //实际上，这里要对数据说明一下
                    //并不是每一行访问行为都有searchKeyword和clickCategoryId两个字段的
                    //其实，只有搜索行为，是有searchKeyword字段的
                    //只有点击品类的行为，是有clickCategoryId字段的
                    //所以，任何一行行为数据，都不可能两个字段都有，所以数据是可能出现null值的

                    //我们决定是否将搜索词和点击品类id拼接到字符串中去
                    //首先要满足：不能是null值
                    //其次，之前的字符串中还没有搜索词或者点击品类id

                    if (StringUtils.isNotEmpty(searchKeyword)) {
                        if (!searchKeywordsBuffer.toString().contains(searchKeyword)) {
                            searchKeywordsBuffer.append(searchKeyword + ",");
                        }
                    }

                    if (clickCategoryId != null) {
                        if (clickCategoryIdsBuffer.toString().contains(String.valueOf(clickCategoryId))) {
                            clickCategoryIdsBuffer.append(clickCategoryId + ",");
                        }
                    }

                    //计算session开始和结束时间
                    //这个计算开始时间和结束时间的逻辑挺特别的，学习一下
                    Date actionTime = DateUtils.parseTime(row.getString(4));

                    if (startTime == null) {
                        startTime = actionTime;
                    }
                    if (endTime == null) {
                        endTime = actionTime;
                    }
                    if (actionTime.before(startTime)) {
                        startTime = actionTime;
                    }
                    if (actionTime.after(endTime)) {
                        endTime = actionTime;
                    }
                    //计算访问步长
                    stepLength++;
                }

                String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

                //计算session访问时长（秒）
                long visitLength = (endTime.getTime() - startTime.getTime()) / 1000;

                //大家思考一下，
                //我们返回的数据格式，即使<sessionid,partAggrInfo>
                //但是，这一步聚合完了以后，其实，我们是还需要将每一行数据，跟对应的的用户信息进行聚合
                //问题就来了，如果是跟用户信息进行聚合的话，那么key，就不应该是sessionid
                //就应该是userid，才能够跟<userid,Row>格式的用户信息进行聚合
                //如果我们这里直接返回<sessionid,partAggrInfo>，还得再做一次mapToPair算子
                //将RDD映射成<userid,partAggrInfo>的格式，那么就多此一举

                //所以，我们这里其实可以直接，返回的数据格式，就是<userid,partAggrInfo>
                //然后跟用户信息join的时候，将partAggrInfo关联上userinfo
                //然后再直接将返回的Tuple的key设置成sessionid
                //最后的数据格式，还是<sessionid,fullAggrInfo>

                //聚合数据，用什么样的格式进行拼接？
                //我们这里统一定义，使用key=value|key=value
                String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionid + "|"
                        + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                        + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds
                        + Constants.FIELD_VISIT_LENGTH + "=" + visitLength + "|"
                        + Constants.FIELD_STEP_LENGTH + "=" + stepLength + "|"
                        + Constants.FIELD_START_TIME + "=" + DateUtils.formatTime(startTime);
                return new Tuple2<Long, String>(userid, partAggrInfo);
            }
        });
        //查询所有用户数据，并映射成<userid,Row>格式
        String sql = "select * from user_info";
        JavaRDD<Row> userInfoRDD = sqlContext.sql(sql).javaRDD();
        JavaPairRDD<Long, Row> user2InfoRDD = userInfoRDD.mapToPair(
                new PairFunction<Row, Long, Row>() {
                    @Override
                    public Tuple2<Long, Row> call(Row row) throws Exception {

                        return new Tuple2<Long, Row>(row.getLong(0), row);
                    }
                });
        /**
         * 这里就可以说一下，比较适合采用reduce join转换为map join的方式
         */

        //将session粒度聚合数据，与用户信息进行join
        JavaPairRDD<Long, Tuple2<String, Row>> userid2FullInfoRDD = userid2PartAggrInfoRDD.join(user2InfoRDD);

        //对join起来的数据进行拼接，并且返回<sessionid,fullAggrInfo>格式的数据
        JavaPairRDD<String, String> sessionid2FullAggrInfoRDD = userid2FullInfoRDD.mapToPair(new PairFunction<Tuple2<Long, Tuple2<String, Row>>, String, String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Tuple2<String, String> call(Tuple2<Long, Tuple2<String, Row>> tuple) throws Exception {
                String partAggrInfo = tuple._2._1;
                Row userInfoRow = tuple._2._2;
                String sessionid = StringUtils.getFieldFromConcatString(partAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                int age = userInfoRow.getInt(3);
                String professional = userInfoRow.getString(4);
                String city = userInfoRow.getString(5);
                String sex = userInfoRow.getString(6);

                String fullAggrInfo = partAggrInfo + "|"
                        + Constants.FIELD_AGE + "=" + age + "|"
                        + Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
                        + Constants.FIELD_CITY + "=" + city + "|"
                        + Constants.FIELD_SEX + "=" + sex;
                return new Tuple2<String, String>(sessionid, fullAggrInfo);
            }
        });

        return sessionid2FullAggrInfoRDD;
    }

}
