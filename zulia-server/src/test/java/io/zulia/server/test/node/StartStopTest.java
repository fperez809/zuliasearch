package io.zulia.server.test.node;

import io.zulia.DefaultAnalyzers;
import io.zulia.ZuliaConstants;
import io.zulia.client.command.Query;
import io.zulia.client.command.Reindex;
import io.zulia.client.command.Store;
import io.zulia.client.config.ClientIndexConfig;
import io.zulia.client.pool.ZuliaWorkPool;
import io.zulia.client.result.QueryResult;
import io.zulia.doc.ResultDocBuilder;
import io.zulia.fields.FieldConfigBuilder;
import io.zulia.message.ZuliaIndex.FacetAs.DateHandling;
import io.zulia.message.ZuliaIndex.FieldConfig.FieldType;
import io.zulia.message.ZuliaQuery;
import io.zulia.message.ZuliaQuery.FacetCount;
import io.zulia.util.ZuliaUtil;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static io.zulia.message.ZuliaQuery.FieldSort.Direction.ASCENDING;
import static io.zulia.message.ZuliaQuery.FieldSort.Direction.DESCENDING;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StartStopTest {

    public static final String FACET_TEST_INDEX = "plugged-54a725bc148f6dd7d62bc600";

    private final int COUNT_PER_ISSN = 10;
    private final String uniqueIdPrefix = "myId-";

    private final String[] issns = new String[]{"1234-1234", "3333-1234", "1234-5555", "1234-4444", "2222-2222"};
    private final String[] eissns = new String[]{"3234-1234", "4333-1234", "5234-5555", "6234-4444", "9222-2222"};

    private int totalRecords = COUNT_PER_ISSN * issns.length;

    private static ZuliaWorkPool zuliaWorkPool;

    @BeforeAll
    public static void initAll() throws Exception {

        TestHelper.createNodes(3);

        TestHelper.startNodes();

        Thread.sleep(2000);

        zuliaWorkPool = TestHelper.createClient();

        ClientIndexConfig indexConfig = new ClientIndexConfig();
        indexConfig.addDefaultSearchField("title");
        indexConfig.addFieldConfig(FieldConfigBuilder.create("title", FieldType.STRING).indexAs(DefaultAnalyzers.STANDARD));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("issn", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD).facet());
        indexConfig.addFieldConfig(FieldConfigBuilder.create("eissn", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("uid", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("an", FieldType.NUMERIC_INT).index());
        indexConfig.addFieldConfig(FieldConfigBuilder.create("country", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD).facet());
        indexConfig.addFieldConfig(FieldConfigBuilder.create("date", FieldType.DATE).index().facetAs(DateHandling.DATE_YYYY_MM_DD));
        indexConfig.setIndexName(FACET_TEST_INDEX);
        indexConfig.setNumberOfShards(1);

        zuliaWorkPool.createIndex(indexConfig);
    }

    @Test
    @Order(2)
    public void index() throws Exception {
        int id = 0;
        {
            for (int j = 0; j < issns.length; j++) {
                for (int i = 0; i < COUNT_PER_ISSN; i++) {
                    boolean half = (i % 2 == 0);
                    boolean tenth = (i % 10 == 0);

                    id++;

                    String uniqueId = uniqueIdPrefix + id;

                    Document mongoDocument = new Document();
                    mongoDocument.put("issn", issns[j]);
                    mongoDocument.put("eissn", eissns[j]);
                    mongoDocument.put("title", "Facet Userguide");

                    if (half) { // 1/2 of input
                        mongoDocument.put("country", "US");

                    } else { // 1/2 of input
                        mongoDocument.put("country", "France");
                    }

                    if (tenth) { // 1/10 of input

                        Date d = Date.from(LocalDate.of(2014, Month.OCTOBER, 4).atStartOfDay(ZoneId.of("UTC")).toInstant());
                        mongoDocument.put("date", d);
                    } else if (half) { // 2/5 of input
                        Date d = Date.from(LocalDate.of(2013, Month.SEPTEMBER, 4).atStartOfDay(ZoneId.of("UTC")).toInstant());
                        mongoDocument.put("date", d);
                    } else { // 1/2 of input
                        Date d = Date.from(LocalDate.of(2013, 8, 4).atStartOfDay(ZoneId.of("UTC")).toInstant());
                        mongoDocument.put("date", d);
                    }

                    Store s = new Store(uniqueId, FACET_TEST_INDEX);

                    ResultDocBuilder resultDocumentBuilder = ResultDocBuilder.newBuilder().setDocument(mongoDocument);
                    if (half) {
                        resultDocumentBuilder.setMetadata(new Document("test", "someValue"));
                    }

                    s.setResultDocument(resultDocumentBuilder);

                    zuliaWorkPool.store(s);
                }
            }
        }
    }

    @Test
    @Order(3)
    public void sortScore() throws Exception {
        Query q = new Query(FACET_TEST_INDEX, "issn:\"1234-1234\" OR country:US", 10);
        q.addFieldSort(ZuliaConstants.SCORE_FIELD, ASCENDING);
        QueryResult queryResult = zuliaWorkPool.query(q);

        double lowScore = -1;
        double highScore = -1;
        for (ZuliaQuery.ScoredResult result : queryResult.getResults()) {
            Assertions.assertTrue(result.getScore() > 0);
            if (lowScore < 0) {
                lowScore = result.getScore();
            }
        }

        q = new Query(FACET_TEST_INDEX, "issn:\"1234-1234\" OR country:US", 10);
        q.addFieldSort(ZuliaConstants.SCORE_FIELD, DESCENDING);
        queryResult = zuliaWorkPool.query(q);

        for (ZuliaQuery.ScoredResult result : queryResult.getResults()) {
            Assertions.assertTrue(result.getScore() > 0);
            if (highScore < 0) {
                highScore = result.getScore();
            }
        }

        Assertions.assertTrue(highScore > lowScore);
    }

    @Test
    @Order(4)
    public void reindex() throws Exception {
        ClientIndexConfig indexConfig = new ClientIndexConfig();
        indexConfig.addDefaultSearchField("title");
        indexConfig.addFieldConfig(FieldConfigBuilder.create("title", FieldType.STRING).indexAs(DefaultAnalyzers.STANDARD));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("issn", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD).facet());
        indexConfig.addFieldConfig(FieldConfigBuilder.create("eissn", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD).facet());
        indexConfig.addFieldConfig(FieldConfigBuilder.create("uid", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("an", FieldType.NUMERIC_INT).index().displayName("Accession Number"));
        indexConfig.addFieldConfig(FieldConfigBuilder.create("country", FieldType.STRING).indexAs(DefaultAnalyzers.LC_KEYWORD).facet());
        indexConfig.addFieldConfig(
                FieldConfigBuilder.create("date", FieldType.DATE).index().facetAs(DateHandling.DATE_YYYY_MM_DD).description("The very special data"));
        indexConfig.setIndexName(FACET_TEST_INDEX);
        indexConfig.setNumberOfShards(1);

        zuliaWorkPool.createIndex(indexConfig);

        zuliaWorkPool.reindex(new Reindex(FACET_TEST_INDEX));

        Query query = new Query(FACET_TEST_INDEX, null, 0).addCountRequest("eissn");

        QueryResult queryResult = zuliaWorkPool.query(query);

        List<FacetCount> eissnCounts = queryResult.getFacetCounts("eissn");

        Assertions.assertEquals(eissns.length, eissnCounts.size());

        for (FacetCount eissnCount : eissnCounts) {
            Assertions.assertEquals(COUNT_PER_ISSN, eissnCount.getCount());
        }
    }

    @Test
    @Order(5)
    public void restart() throws Exception {
        TestHelper.stopNodes();
        Thread.sleep(2000);
        TestHelper.startNodes();
        Thread.sleep(2000);
    }

    @Test
    @Order(6)
    public void confirm() throws Exception {
        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addCountRequest("issn", 30);
            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(totalRecords, qr.getTotalHits(), "Total record count mismatch");

            Assertions.assertEquals(issns.length, qr.getFacetCounts("issn").size(), "Total facets mismatch");

            for (FacetCount fc : qr.getFacetCounts("issn")) {
                Assertions.assertEquals(COUNT_PER_ISSN, fc.getCount(), "Count for facet <" + fc.getFacet() + "> mismatch");
            }

        }

        {

            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addCountRequest("date", 30);
            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(totalRecords, qr.getTotalHits(), "Total record count mismatch");
            Assertions.assertEquals(3, qr.getFacetCounts("date").size(), "Total facets mismatch");

            for (@SuppressWarnings("unused") FacetCount fc : qr.getFacetCounts("date")) {
                //System.out.println(fc);
            }

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10);
            q.addDrillDown("issn", "1234-1234").addDrillDown("country", "France");
            q.addCountRequest("issn");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(COUNT_PER_ISSN / 2, qr.getTotalHits(), "Total record count after drill down mismatch");
            Assertions.assertEquals(1, qr.getFacetCounts("issn").size(), "Number of issn facets  mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("date", "2014-10-04");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(totalRecords / 10, qr.getTotalHits(), "Total record count after drill down mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("date", "2013-09-04");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals((totalRecords * 2) / 5, qr.getTotalHits(), "Total record count after drill down mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("date", "2013-08-04");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(totalRecords / 2, qr.getTotalHits(), "Total record count after drill down mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("issn", "1234-1234");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(COUNT_PER_ISSN, qr.getTotalHits(), "Total record count after drill down mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("issn", "1234-1234").addDrillDown("issn", "3333-1234");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(COUNT_PER_ISSN * 2, qr.getTotalHits(), "Total record count after drill down mismatch");

        }
        {
            Query q = new Query(FACET_TEST_INDEX, "title:userguide", 10).addDrillDown("issn", "1234-1234").addDrillDown("country", "France");

            QueryResult qr = zuliaWorkPool.query(q);

            Assertions.assertEquals(COUNT_PER_ISSN / 2, qr.getTotalHits(), "Total record count after drill down mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "country:US", 10).setResultFetchType(ZuliaQuery.FetchType.META);

            QueryResult qr = zuliaWorkPool.query(q);

            for (ZuliaQuery.ScoredResult result : qr.getResults()) {
                Document metadata = ZuliaUtil.byteStringToMongoDocument(result.getResultDocument().getMetadata());
                Assertions.assertEquals("someValue", metadata.getString("test"));
            }

            Assertions.assertEquals(totalRecords / 2, qr.getTotalHits(), "Total record count filtered on half mismatch");

        }

        {
            Query q = new Query(FACET_TEST_INDEX, "country:US", 10).setResultFetchType(ZuliaQuery.FetchType.FULL);

            QueryResult qr = zuliaWorkPool.query(q);

            for (ZuliaQuery.ScoredResult result : qr.getResults()) {
                Document metadata = ZuliaUtil.byteStringToMongoDocument(result.getResultDocument().getMetadata());
                Assertions.assertEquals("someValue", metadata.getString("test"));
            }

            Assertions.assertEquals(totalRecords / 2, qr.getTotalHits(), "Total record count filtered on half mismatch");

        }

    }

    @Test
    @Order(7)
    public void shutdown() throws Exception {
        TestHelper.stopNodes();
        zuliaWorkPool.shutdown();
    }
}
