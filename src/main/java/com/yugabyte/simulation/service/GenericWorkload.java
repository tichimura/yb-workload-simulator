package com.yugabyte.simulation.service;

import com.yugabyte.simulation.dao.*;
import com.yugabyte.simulation.services.ServiceManager;
import com.yugabyte.simulation.workload.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Repository
public class GenericWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceManager serviceManager;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

	@Override
	public String getName() {
		return "Generic" + ((applicationName != null && !applicationName.equals(""))? " ["+applicationName+"]" : "");
	}

    private static final String CREATE_GENERIC1 = "create table if not exists generic1(\n" +
            "   pkid uuid,\n" +
            "   col1 int,\n" +
            "   col2 int,\n" +
            "   col3 int,\n" +
            "   col4 int,\n" +
            "   col5 numeric,\n" +
            "   col6 numeric,\n" +
            "   col7 numeric,\n" +
            "   col8 timestamp default now(),\n" +
            "   col9 timestamp default now(),\n" +
            "   primary key (pkid)\n" +
            ");";

    private static final String CREATE_GENERIC2 = "create table  if not exists generic2(\n" +
            "   pkid uuid,\n" +
            "   rawdatacol varchar(30) ,\n" +
            "   primary key (pkid)\n" +
            ");";

    private static final String CREATE_GENERIC3 = "create table if not exists generic3(\n" +
            "   pkid uuid,\n" +
            "   col1 varchar(255),\n" +
            "   rawdatacol varchar(30),\n" +
            "   primary key (pkid)\n" +
            ");";



    private static final String DROP_GENERIC1 = "drop table if exists generic1 cascade;";
    private static final String TRUNCATE_GENERIC1 = "truncate generic1;";
    private static final String DROP_GENERIC2 = "drop table if exists generic2 cascade;";
    private static final String TRUNCATE_GENERIC2 = "truncate generic2;";
    private static final String DROP_GENERIC3 = "drop table if exists generic3 cascade;";
    private static final String TRUNCATE_GENERIC3 = "truncate generic3;";

    // column 8 and 9 in table 1 are timestamps. I will let db populate those.
    private static final String INSERT_RECORD_GENERIC1 = "insert into generic1(pkid, col1, col2, col3, col4, col5, col6, col7) values(?,?,?,?,?,?,?,?);";
    private static final String INSERT_RECORD_GENERIC2 = "insert into generic2(pkid, rawdatacol) values(?,?);";
    private static final String INSERT_RECORD_GENERIC3 = "insert into generic3(pkid, col1, rawdatacol) values(?,?,?);";

    private final String POINT_SELECT_QUERY_GENERIC1 = "select pkid,col1,col2,col3,col4,col5,col6,col7,col8,col9 from generic1 where pkid = ?::uuid;";
    private final String POINT_SELECT_QUERY_GENERIC2 = "select pkid,rawdatacol from generic2 where pkid = ?::uuid;";
    private final String POINT_SELECT_QUERY_GENERIC3 = "select pkid,col1,rawdatacol from generic3 where pkid = ?::uuid;";

    private final String SELECT_QUERY_ON_BINARYCOL_GENERIC2 = "select pkid,rawdatacol from generic2 where rawdatacol like ?::bytea limit 100;";
    private final String SELECT_QUERY_ON_BINARYCOL_GENERIC3 = "select pkid,col1,rawdatacol from generic3 where rawdatacol like ?::bytea limit 100;";

    private static final int ROWS_TO_PRELOAD = 10000;

    private enum WorkloadType {
        CREATE_TABLES,
        SEED_DATA,
        RUN_SIMULATION_FIXED_WORKLOAD,
        RUN_SIMULATION,
        RUN_LIKE_QUERY_ON_GENERIC2,
        RUN_LIKE_QUERY_ON_GENERIC3,
        START_NODE,
        STOP_NODE
    }

    private final FixedStepsWorkloadType createTablesWorkloadType;
    private final FixedTargetWorkloadType seedingWorkloadType;
    private final ThroughputWorkloadType runInstanceType;
    private final FixedTargetWorkloadType simulationFixedWorkloadType;

    public GenericWorkload() {
        this.createTablesWorkloadType = new FixedStepsWorkloadType(
                new Step("Drop generic1", (a,b) -> jdbcTemplate.execute(DROP_GENERIC1)),
                new Step("Create generic1", (a,b) -> jdbcTemplate.execute(CREATE_GENERIC1)),
                new Step("Drop generic2", (a,b) -> jdbcTemplate.execute(DROP_GENERIC2)),
                new Step("Create generic2", (a,b) -> jdbcTemplate.execute(CREATE_GENERIC2)),
                new Step("Drop generic3", (a,b) -> jdbcTemplate.execute(DROP_GENERIC3)),
                new Step("Create generic3", (a,b) -> jdbcTemplate.execute(CREATE_GENERIC3))
        );

        this.seedingWorkloadType = new FixedTargetWorkloadType();
        this.runInstanceType = new ThroughputWorkloadType();
        this.simulationFixedWorkloadType = new FixedTargetWorkloadType();
    }

    private WorkloadDesc createTablesWorkload = new WorkloadDesc(
            GenericWorkload.WorkloadType.CREATE_TABLES.toString(),
            "テーブルの作成",
            "テーブルを作成する。テーブルがすでに存在する場合は削除される。"
    );

    private WorkloadDesc seedingWorkload = new WorkloadDesc(
            GenericWorkload.WorkloadType.SEED_DATA.toString(),
            "シードデータ",
            "3つのテーブルにデータをロードする（チャートのレイテンシは3回のインサートの累積値を表示する）",
            new WorkloadParamDesc("生成するレコード数", 1, Integer.MAX_VALUE, 1000),
            new WorkloadParamDesc("スレッド", 1, 500, 32)
    );

    private WorkloadDesc runningWorkload = new WorkloadDesc(
            GenericWorkload.WorkloadType.RUN_SIMULATION.toString(),
            "シミュレーション - old",
            "3つのテーブルからのリードのシミュレーションを実行する（チャート上のレイテンシは、3つのセレクトと3つのインサートの累積値を表示します）。",
            new WorkloadParamDesc("スループット (tps)", 1, 1000000, 500),
            new WorkloadParamDesc("最大スレッド数", 1, 500, 64),
            new WorkloadParamDesc("Include new Inserts (to 3 tables)", false)
    );

    private WorkloadDesc simulationFixedWorkload = new WorkloadDesc(
            GenericWorkload.WorkloadType.RUN_SIMULATION_FIXED_WORKLOAD.toString(),
            "シミュレーション",
            "3つのテーブルからのリードのシミュレーションを実行する（チャート上のレイテンシは、3つのセレクトと3つのインサートの累積値を表示します）。",
            new WorkloadParamDesc("呼び出し回数", 1, 10000000, 1000000),
            new WorkloadParamDesc("最大スレッド数", 1, 500, 64),
            new WorkloadParamDesc("Include new Inserts (to 3 tables)", false)
    );


    @Override
    public List<WorkloadDesc> getWorkloads() {
        return Arrays.asList(
                createTablesWorkload
                , seedingWorkload
                , simulationFixedWorkload
//                , runningWorkload
        );
    }


    @Override
    public InvocationResult invokeWorkload(String workloadId, ParamValue[] values) {
        GenericWorkload.WorkloadType type = GenericWorkload.WorkloadType.valueOf(workloadId);
        try {
            switch (type) {
                case CREATE_TABLES:
                    this.createTables();
                    return new InvocationResult("Ok");
                case SEED_DATA:
                    this.seedData(values[0].getIntValue(), values[1].getIntValue());
                    return new InvocationResult("Ok");
                case RUN_SIMULATION:
                    this.runSimulation(values);
                    return new InvocationResult("Ok");
                case RUN_SIMULATION_FIXED_WORKLOAD:
                    this.runSimulationFixedWorkload(values);
                    return new InvocationResult("Ok");
                case STOP_NODE:
                    return new InvocationResult("Ok");
                case START_NODE:
                    return new InvocationResult("Ok");

            }
            throw new IllegalArgumentException("Unknown workload "+ workloadId);
        }
        catch (Exception e) {
            return new InvocationResult(e);
        }
    }

    private void createTables() {
        createTablesWorkloadType.createInstance(serviceManager).execute();
    }

    private void seedData(int numberToGenerate, int threads) {
        seedingWorkloadType
                .createInstance(serviceManager)
                .execute(threads, numberToGenerate, (customData, threadData) -> {
                    runInserts();
//                    UUID uuid = LoadGeneratorUtils.getUUID();
//                    jdbcTemplate.update(INSERT_RECORD_GENERIC1,
//                            uuid,
//                            LoadGeneratorUtils.getInt(0, 100),
//                            LoadGeneratorUtils.getInt(20, 300),
//                            LoadGeneratorUtils.getInt(100, 1000),
//                            LoadGeneratorUtils.getInt(0, 1000),
//                            LoadGeneratorUtils.getDouble(),
//                            LoadGeneratorUtils.getDouble(),
//                            LoadGeneratorUtils.getDouble()
//                    );
//                    jdbcTemplate.update(INSERT_RECORD_GENERIC2,
//                            uuid,
//                            LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,30))
//                    );
//                    jdbcTemplate.update(INSERT_RECORD_GENERIC3,
//                            uuid,
//                            LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,255)),
//                            LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,30))
//                    );
                    return threadData;
                });
    }


    /*
    Assume some number of ranges: 64 (does not really have to map to number of tablets)
    From each range get these many rows: 10K rows needed DIVIDED BY num_ranges --> ~156 rows/range.
    From these 64 hash code ranges, now ask for 156 rows each.

    SELECT pkid FROM T where yb_hash_code(pkid) >= 0 and yb_hash_code(pkid) < 1024 LIMIT 156
    UNION ALL
    SELECT pkid FROM T where yb_hash_code(pkid) >= 1024 and yb_hash_code(pkid) < 2048 LIMIT 156
    ..
    ...
    UNION ALL
    SELECT pkid FROM T where yb_hash_code(pkid) >= 64512 and yb_hash_code(pkid) < 65536
     */

    private List<UUID> getQueryList() {
        List<UUID> results = new ArrayList<UUID>(ROWS_TO_PRELOAD);
        int numOfRanges = 64;
        int limit = ROWS_TO_PRELOAD/numOfRanges;
        int runningHashCodeVal = 0;
        StringBuffer sbQuery = new StringBuffer();

        while(runningHashCodeVal < 65536){
            if(runningHashCodeVal != 0){
                sbQuery.append(" UNION ALL ");
            }
            int nextHashVal = runningHashCodeVal + 1024;
            sbQuery.append(" (SELECT pkid FROM generic1 where yb_hash_code(pkid) >= "+runningHashCodeVal+" and yb_hash_code(pkid) < "+nextHashVal+" LIMIT "+limit+") ");
            runningHashCodeVal = nextHashVal;
        }



        jdbcTemplate.setMaxRows(ROWS_TO_PRELOAD);
        jdbcTemplate.setFetchSize(ROWS_TO_PRELOAD);
        System.out.println("query:"+sbQuery.toString());
        jdbcTemplate.query(sbQuery.toString(),
                new RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
                        UUID value = (UUID)rs.getObject(1);
                        results.add(value);
                    }
                });

//        System.out.println("list of pkids:");
//        for(UUID pkId: results){
//            System.out.print(pkId.toString()+",");
//        }

        return results;
    }

    private void runSimulationFixedWorkload(ParamValue[] values) {
        int numOfInvocations = values[0].getIntValue();
        int maxThreads = values[1].getIntValue();
        boolean runInserts = values[2].getBoolValue();
        System.out.println("**** Preloading data...");
        final List<UUID> uuids = getQueryList();
        System.out.println("**** Preloading complete...");
        Random random = ThreadLocalRandom.current();
        seedingWorkloadType
                .createInstance(serviceManager)
                .execute(maxThreads, numOfInvocations, (customData, threadData) -> {
                    UUID id = uuids.get(random.nextInt(uuids.size()));
                    runPointReadgeneric1(id);
                    runPointReadgeneric2(id);
                    runPointReadgeneric3(id);
                    if(runInserts){
                        runInserts();
                    }
                    return threadData;
                });
    }

    private void runSimulation(ParamValue[] values) {
    	int tps = values[0].getIntValue();
    	int maxThreads = values[1].getIntValue();
    	boolean runInserts = values[2].getBoolValue();

    		System.out.println("**** Preloading data...");
        final List<UUID> uuids = getQueryList();
        System.out.println("**** Preloading complete...");

        Random random = ThreadLocalRandom.current();
        jdbcTemplate.setFetchSize(1000);

        runInstanceType
                .createInstance(serviceManager, this.runningWorkload, values)
                .setMaxThreads(maxThreads)
                .execute(tps, (customData, threadData) -> {
                    UUID id = uuids.get(random.nextInt(uuids.size()));
                    runPointReadgeneric1(id);
                    runPointReadgeneric2(id);
                    runPointReadgeneric3(id);

                    if(runInserts){
                        runInserts();
                    }
                });
    }

    private void runPointReadgeneric1(UUID id){
        String query = POINT_SELECT_QUERY_GENERIC1;
        jdbcTemplate.query(query, new Object[] {id}, new int[] {Types.VARCHAR},
                new RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
/*                                    System.out.printf("pkid=%s, col1='%s', col2=%s, col3=%s, col4=%s, col5=%s, col6=%s, col7=%s, col8=%s, col9=%s \n",
                                            rs.getString("pkid"),
                                            rs.getInt("col1"),
                                            rs.getInt("col2"),
                                            rs.getInt("col3"),
                                            rs.getInt("col4"),
                                            rs.getDouble("col5"),
                                            rs.getDouble("col6"),
                                            rs.getDouble("col7"),
                                            rs.getTimestamp("col8"),
                                            rs.getTimestamp("col9")

                                    );*/
                    }
                });
    }

    private void runPointReadgeneric2(UUID id){
        String query = POINT_SELECT_QUERY_GENERIC2;
        jdbcTemplate.query(query, new Object[] {id}, new int[] {Types.VARCHAR},
                new RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
/*                                    System.out.printf("pkid=%s, rawdatacol='%s' \n",
                                            rs.getString("pkid"),
                                            rs.getBytes("rawdatacol") != null?rs.getBytes("rawdatacol").length:null
                                    );*/
                    }
                });
    }

    private void runPointReadgeneric3(UUID id){
        String query = POINT_SELECT_QUERY_GENERIC3;
        jdbcTemplate.query(query, new Object[] {id}, new int[] {Types.VARCHAR},
                new RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
/*                        System.out.printf("pkid=%s, col1='%s' , rawdatacol='%s' \n",
                                rs.getString("pkid"),
                                rs.getString("col1"),
                                rs.getBytes("rawdatacol") != null?rs.getBytes("rawdatacol").length:null
                        );*/
                    }
                });
    }

    private void runInserts(){
        UUID uuid = LoadGeneratorUtils.getUUID();
        jdbcTemplate.update(INSERT_RECORD_GENERIC1,
                uuid,
                LoadGeneratorUtils.getInt(0, 100),
                LoadGeneratorUtils.getInt(20, 300),
                LoadGeneratorUtils.getInt(100, 1000),
                LoadGeneratorUtils.getInt(0, 1000),
                LoadGeneratorUtils.getDouble(),
                LoadGeneratorUtils.getDouble(),
                LoadGeneratorUtils.getDouble()
        );
        jdbcTemplate.update(INSERT_RECORD_GENERIC2,
                uuid,
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,30))
        );
        jdbcTemplate.update(INSERT_RECORD_GENERIC3,
                uuid,
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,255)),
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(1,30))
        );
    }


}

