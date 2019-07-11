package com.ai.paas.ipaas.search.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.sort.GeoDistanceSortBuilder;

import com.ai.paas.ipaas.search.SearchRuntimeException;
import com.ai.paas.ipaas.search.common.TypeGetter;
import com.ai.paas.ipaas.search.vo.AggField;
import com.ai.paas.ipaas.search.vo.AggResult;
import com.ai.paas.ipaas.search.vo.SearchCriteria;
import com.ai.paas.ipaas.search.vo.SearchOption;
import com.ai.paas.ipaas.search.vo.Sort;
import com.ai.paas.ipaas.util.StringUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class SearchHelper {

    private static final int MATCH_PHRASE_SLOP = 50;

    private String dtFmt = "yyyy-MM-dd'T'HH:mm:ssZZZ";

    private Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").create();

    public void setDateFmt(String dateFmt) {
        gson = new GsonBuilder().setDateFormat(dateFmt).create();
        this.dtFmt = dateFmt;
    }

    public String getId(String json, String id) {
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        if (null != obj.get(id))
            return obj.get(id).getAsString();
        else
            return null;
    }

    public String getId(XContentBuilder builder, String id) {
        String json;
        try {
            json = builder.string();
            return getId(json, id);
        } catch (IOException e) {
            throw new SearchRuntimeException(builder.toString(), e);
        }
    }

    public boolean hasId(XContentBuilder builder, String id) {
        String json;
        try {
            json = builder.string();
            return hasId(json, id);
        } catch (IOException e) {
            throw new SearchRuntimeException(builder.toString(), e);
        }
    }

    public boolean hasId(String json, String id) {

        JsonObject obj = gson.fromJson(json, JsonObject.class);
        return null != obj.get(id);
    }

    public QueryBuilder createStringSQLBuilder(String query) {
        try {
            if (StringUtil.isBlank(query)) {
                return null;
            }
            return QueryBuilders.queryStringQuery(query);
        } catch (Exception e) {
            throw new SearchRuntimeException("ES create query builder error", e);

        }
    }

    /**
     * 搜索条件，支持嵌套逻辑
     * 
     * @param searchCriterias
     * @return 索引对象
     */
    public QueryBuilder createQueryBuilder(List<SearchCriteria> searchCriterias) {
        try {
            if (searchCriterias == null || searchCriterias.isEmpty()) {
                return null;
            }
            BoolQueryBuilder rootQueryBuilder = QueryBuilders.boolQuery();
            for (SearchCriteria searchCriteria : searchCriterias) {
                if (searchCriteria.hasSubCriteria()) {
                    // 循环调用
                    QueryBuilder childQueryBuilder = createQueryBuilder(searchCriteria.getSubCriterias());
                    if (childQueryBuilder != null) {
                        searchCriteria.getOption().getSearchLogic().convertQueryBuilder(rootQueryBuilder,
                                childQueryBuilder);
                    }
                }

                QueryBuilder builder = searchCriteria.getBuilder();
                // 如果原生的查询不为空，则优先用原生的查询条件。gucl--20190219
                if (builder != null) {
                    searchCriteria.getOption().getSearchLogic().convertQueryBuilder(rootQueryBuilder, builder);
                }
                // 原生的查询条件为空时，按自定义的查询条件
                else {
                    String field = getLowerFormatField(searchCriteria.getField());
                    if (!StringUtil.isBlank(field)) {
                        SearchOption searchOption = searchCriteria.getOption();
                        QueryBuilder queryBuilder = createSingleFieldQueryBuilder(field, searchCriteria.getFieldValue(),
                                searchOption);
                        if (queryBuilder != null) {
                            searchCriteria.getOption().getSearchLogic().convertQueryBuilder(rootQueryBuilder,
                                    queryBuilder);
                        }
                    } // end if
                }

            }
            return rootQueryBuilder;
        } catch (Exception e) {
            throw new SearchRuntimeException("ES create builder error", e);

        }
    }

    private String getLowerFormatField(String field) {
        if (StringUtil.isBlank(field))
            return null;
        return field;
    }

    /*
     * 创建过滤条件
     */
    public QueryBuilder createSingleFieldQueryBuilder(String field, List<Object> values, SearchOption mySearchOption) {
        try {

            if (mySearchOption.getSearchType() == SearchOption.SearchType.range) {
                /* 区间搜索 */
                return createRangeQueryBuilder(field, values);
            }
            QueryBuilder queryBuilder = null;
            // 这里先加个是否存在判断
            if (mySearchOption.getDataFilter() == SearchOption.DataFilter.exists) {
                // 需要增加filter搜索
                queryBuilder = QueryBuilders.existsQuery(field);
                return queryBuilder;
            } else if (values != null) {
                String formatValue = null;
                List<String> terms = new ArrayList<>();
                Iterator<Object> iterator = values.iterator();
                Object obj = null;
                while (iterator.hasNext()) {
                    queryBuilder = null;
                    obj = iterator.next();
                    // 这里得判断是否为空
                    if (null == obj)
                        continue;
                    formatValue = obj.toString().trim().replaceAll("\\*", "");// 格式化搜索数据
                    formatValue = formatValue.replaceAll("\\^", "");// 格式化搜索数据
                    formatValue = QueryParser.escape(formatValue);
                    formatValue = formatValue.replaceAll("\\\\-", "-");
                    if (mySearchOption.getSearchType() == SearchOption.SearchType.querystring) {
                        /*
                         * 如果搜索长度为1的非数字的字符串，格式化为通配符搜索，暂时这样， 以后有时间改成multifield搜索 ，就不需要通配符了
                         */
                        if (formatValue.length() == 1 && !Pattern.matches("[0-9]", formatValue)) {
                            formatValue = "*" + formatValue + "*";
                        }
                    }
                    terms.add(formatValue);
                }
                String qryValue = StringUtils.join(terms, " ");
                // 在搜索精确匹配时按精确走
                if (mySearchOption.getSearchType() == SearchOption.SearchType.term) {
                    queryBuilder = QueryBuilders.termsQuery(field, terms).boost(mySearchOption.getBoost());
                } else if (mySearchOption.getSearchType() == SearchOption.SearchType.querystring) {
                    QueryStringQueryBuilder queryStringQueryBuilder = QueryBuilders
                            .queryStringQuery(mySearchOption.getTermOperator() == SearchOption.TermOperator.AND
                                    ? "\\\"" + qryValue + "\\\""
                                    : qryValue)
                            .defaultOperator(mySearchOption.getTermOperator() == SearchOption.TermOperator.AND
                                    ? QueryStringQueryBuilder.Operator.AND
                                    : QueryStringQueryBuilder.Operator.OR)
                            .minimumShouldMatch(mySearchOption.getQueryStringPrecision());
                    queryBuilder = queryStringQueryBuilder.field(field).boost(mySearchOption.getBoost());
                } else if (mySearchOption.getSearchType() == SearchOption.SearchType.match) {
                    queryBuilder = QueryBuilders.matchQuery(field, qryValue)
                            .operator(mySearchOption.getTermOperator() == SearchOption.TermOperator.AND
                                    ? MatchQueryBuilder.Operator.AND
                                    : MatchQueryBuilder.Operator.OR)
                            .minimumShouldMatch(mySearchOption.getQueryStringPrecision())
                            .type(MatchQueryBuilder.Type.PHRASE).slop(MATCH_PHRASE_SLOP);

                }
            }
            return queryBuilder;
        } catch (Exception e) {
            throw new SearchRuntimeException("ES create builder error", e);
        }
    }

    private RangeQueryBuilder createRangeQueryBuilder(String field, List<Object> valuesSet) {
        // 这里需要判断下范围，是单值也可以
        if (null == valuesSet || valuesSet.isEmpty()) {
            return null;
        }
        Object[] values = valuesSet.toArray(new Object[valuesSet.size()]);

        boolean timeType = false;
        if (SearchOption.isDate(values[0])) {
            timeType = true;
        }
        String begin = "";
        String end = "";
        if (timeType) {
            /*
             * 如果时间类型的区间搜索出现问题，有可能是数据类型导致的：
             * （1）在监控页面（elasticsearch-head）中进行range搜索，看看什么结果，如果也搜索不出来，则：
             * （2）请确定mapping中是date类型，格式化格式是yyyy-MM-dd HH:mm:ss （3）请确定索引里的值是类似2012-01-01
             * 00:00:00的格式 （4）如果是从数据库导出的数据，请确定数据库字段是char或者varchar类型，而不是date类型（此类型可能会有问题）
             */
            begin = SearchOption.formatDate(this.dtFmt, values[0]);
            if (values.length > 1)
                end = SearchOption.formatDate(this.dtFmt, values[1]);
        } else {
            begin = null == values[0] ? null : values[0].toString();
            if (values.length > 1)
                end = null == values[1] ? null : values[1].toString();
            if (null != begin) {
                begin = begin.toString().trim().replaceAll("\\*", "");// 格式化搜索数据
                begin = begin.replaceAll("\\^", "");// 格式化搜索数据
                begin = QueryParser.escape(begin);
                begin = begin.replaceAll("\\\\-", "-");
            }
            if (null != end) {
                end = end.trim().replaceAll("\\*", "");// 格式化搜索数据
                end = end.replaceAll("\\^", "");// 格式化搜索数据
                end = QueryParser.escape(end);
                end = end.replaceAll("\\\\-", "-");
            }
        }
        if (StringUtil.isBlank(end))
            return QueryBuilders.rangeQuery(field).from(begin);
        else
            return QueryBuilders.rangeQuery(field).from(begin).to(end);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getSearchResult(TransportClient client, SearchResponse response, Class<T> clazz,
            @SuppressWarnings("rawtypes") TypeGetter typeGetter, int from, int offset, List<Sort> sorts) {
        try {
            // 判断sorts中是否包含geo经纬度排序 为-1说明不存在
            int geoSortIndex = getGeoSortIndex(sorts);

            List<T> results = new ArrayList<>();
            SearchHits hits = response.getHits();
            if (hits.getTotalHits() == 0) {
                return results;
            }
            int start = -1;
            while (true) {
                for (SearchHit hit : response.getHits().getHits()) {
                    start++;
                    // from， offset处
                    // Handle the hit...
                    if (start >= from && start < (from + offset)) {
                        // 存在经纬度排序，则计算直线距离 --gucl -20190219
                        Map<String, Object> hitMap = null;
                        if (geoSortIndex > -1) {
                            // 获取距离值，并保留两位小数点
                            BigDecimal geoDis = BigDecimal.valueOf((Double) hit.getSortValues()[geoSortIndex]);
                            hitMap = hit.getSource();
                            // 在创建MAPPING的时候，属性名的不可为geoDistance。
                            hitMap.put("geoDistance", geoDis.setScale(5, RoundingMode.HALF_DOWN));
                        }

                        // 找到位置，开始存储，到偏移就结束
                        String source = hit.getSourceAsString();
                        if (hitMap != null) {
                            source = gson.toJson(hitMap);
                        }
                        if (null != clazz && !clazz.isAssignableFrom(String.class)) {
                            results.add(gson.fromJson(source, clazz));
                        } else if (null != typeGetter) {
                            results.add((T) gson.fromJson(source, typeGetter.getType()));
                        } else {
                            results.add((T) source);
                        }
                    } else if (start >= (from + offset)) {
                        // 退到外层
                        break;
                    }
                }
                if (start >= (from + offset)) {
                    // 退出while
                    break;
                }
                response = client.prepareSearchScroll(response.getScrollId()).setScroll(new TimeValue(60000)).execute()
                        .actionGet();
                // Break condition: No hits are returned
                if (response.getHits().getHits().length == 0) {
                    break;
                }
            }
            return results;
        } catch (Exception e) {
            throw new SearchRuntimeException("ES search error", e);
        }
    }

    // 获取地理位置排序的索引
    private int getGeoSortIndex(List<Sort> sorts) {
        int res = -1;
        if (sorts != null && !sorts.isEmpty()) {
            for (int i = 0; i < sorts.size(); i++) {
                Sort sort = sorts.get(i);
                // 获取地理位置索引
                if (sort.getSortBuilder() instanceof GeoDistanceSortBuilder) {
                    res = i;
                    break;
                }
            }
        }
        return res;
    }

    public String getSearchResult(SearchResponse response) {
        try {
            SearchHits hits = response.getHits();
            if (hits.getTotalHits() == 0) {
                return null;
            }
            String source = null;
            List<String> results = new ArrayList<>();
            for (SearchHit searchHit : hits.getHits()) {
                source = searchHit.getSourceAsString();
                results.add(source);
            }
            return gson.toJson(results);
        } catch (Exception e) {
            throw new SearchRuntimeException("ES search error", e);
        }
    }

    public SearchRequestBuilder createHighlight(SearchRequestBuilder searchRequestBuilder,
            List<SearchCriteria> searchCriterias, String highlightCSS) {
        for (SearchCriteria searchCriteria : searchCriterias) {
            String field = getLowerFormatField(searchCriteria.getField());
            SearchOption searchOption = searchCriteria.getOption();
            if (searchOption.isHighlight()) {
                /*
                 * http://www.elasticsearch.org/guide/reference/api/search/ highlighting.html
                 * 
                 * fragment_size设置成1000，默认值会造成返回的数据被截断
                 */
                searchRequestBuilder = searchRequestBuilder.addHighlightedField(field, 1000)
                        .setHighlighterPreTags("<" + highlightCSS.split(",")[0] + ">")
                        .setHighlighterPostTags("</" + highlightCSS.split(",")[1] + ">");

            }
        }

        return searchRequestBuilder;
    }

    public List<AggResult> getAgg(SearchResponse searchResponse, List<AggField> fields) {
        List<AggResult> aggResults = new ArrayList<>();
        for (AggField field : fields) {
            Terms sortAggregate = searchResponse.getAggregations().get(field.getField() + "_aggs");
            if (null != sortAggregate && null != sortAggregate.getBuckets()) {
                for (Terms.Bucket entry : sortAggregate.getBuckets()) {
                    // 新建一个对象
                    AggResult aggResult = new AggResult(entry.getKeyAsString(), entry.getDocCount(), field.getField());
                    // 嵌套循环
                    List<AggResult> temp = getSubAgg(entry, field.getSubAggs());
                    aggResult.setSubResult(temp);
                    // 加到list
                    aggResults.add(aggResult);
                }
            }
        }
        return aggResults;
    }

    private List<AggResult> getSubAgg(Terms.Bucket entry, List<AggField> fields) {
        List<AggResult> aggResults = new ArrayList<>();
        if (null == entry || null == fields || fields.isEmpty())
            return aggResults;

        for (AggField field : fields) {
            Terms subAgg = entry.getAggregations().get(field.getField() + "_aggs");
            if (null != subAgg) {
                aggResults = new ArrayList<>();
                for (Terms.Bucket subEntry : subAgg.getBuckets()) {
                    AggResult aggResult = new AggResult(subEntry.getKeyAsString(), entry.getDocCount(),
                            field.getField());
                    // 嵌套循环
                    List<AggResult> temp = getSubAgg(entry, field.getSubAggs());
                    if (null != temp)
                        aggResult.setSubResult(temp);
                    // 加到list
                    aggResults.add(aggResult);
                }
            }
        }
        return aggResults;
    }

    public TermsBuilder addSubAggs(TermsBuilder termBuilder, List<AggField> subFields) {
        if (null == subFields || subFields.isEmpty())
            return termBuilder;
        for (AggField field : subFields) {
            termBuilder
                    .subAggregation(
                            AggregationBuilders.terms(field.getField() + "_aggs").field(field.getField() + ".raw"))
                    .size(100);
            termBuilder = addSubAggs(termBuilder, field.getSubAggs());
        }
        return termBuilder;
    }

}
