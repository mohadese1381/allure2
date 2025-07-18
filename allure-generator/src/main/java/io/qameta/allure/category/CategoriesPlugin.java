/*
 *  Copyright 2016-2024 Qameta Software Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.qameta.allure.category;

import com.fasterxml.jackson.core.type.TypeReference;
import io.qameta.allure.Aggregator2;
import io.qameta.allure.CommonCsvExportAggregator2;
import io.qameta.allure.CommonJsonAggregator2;
import io.qameta.allure.CompositeAggregator2;
import io.qameta.allure.Constants;
import io.qameta.allure.Reader;
import io.qameta.allure.ReportStorage;
import io.qameta.allure.context.JacksonContext;
import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.LaunchResults;
import io.qameta.allure.core.ResultsVisitor;
import io.qameta.allure.csv.CsvExportCategory;
import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.TestResult;
import io.qameta.allure.tree.DefaultTreeLayer;
import io.qameta.allure.tree.TestResultTree;
import io.qameta.allure.tree.TestResultTreeGroup;
import io.qameta.allure.tree.Tree;
import io.qameta.allure.tree.TreeLayer;
import io.qameta.allure.tree.TreeWidgetData;
import io.qameta.allure.tree.TreeWidgetItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.qameta.allure.entity.Statistic.comparator;
import static io.qameta.allure.entity.TestResult.comparingByTimeAsc;
import static io.qameta.allure.tree.TreeUtils.calculateStatisticByLeafs;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Plugin that generates data for Categories tab.
 *
 * @since 2.0
 */
@SuppressWarnings({"ClassDataAbstractionCoupling"})
public class CategoriesPlugin extends CompositeAggregator2 implements Reader {

    public static final String CATEGORIES = "categories";

    public static final Category FAILED_TESTS = new Category().setName("Product defects");

    public static final Category BROKEN_TESTS = new Category().setName("Test defects");

    public static final String JSON_FILE_NAME = "categories.json";

    public static final String CSV_FILE_NAME = "categories.csv";

    //@formatter:off
    private static final TypeReference<List<Category>> CATEGORIES_TYPE =
        new TypeReference<List<Category>>() { };
    //@formatter:on

    public CategoriesPlugin() {
        super(Arrays.asList(
                new EnrichDataAggregator(), new JsonAggregator(),
                new CsvExportAggregator(), new WidgetAggregator()
        ));
    }

    @Override
    public void readResults(final Configuration configuration,
                            final ResultsVisitor visitor,
                            final Path directory) {
        final JacksonContext context = configuration.requireContext(JacksonContext.class);
        final Path categoriesFile = directory.resolve(JSON_FILE_NAME);
        if (Files.exists(categoriesFile)) {
            try (InputStream is = Files.newInputStream(categoriesFile)) {
                final List<Category> categories = context.getValue().readValue(is, CATEGORIES_TYPE);
                visitor.visitExtra(CATEGORIES, categories);
            } catch (IOException e) {
                visitor.error("Could not read categories file " + categoriesFile, e);
            }
        }
    }

    /* default */ static Tree<TestResult> getData(final List<LaunchResults> launchResults) {

        // @formatter:off
        final Tree<TestResult> categories = new TestResultTree(CATEGORIES, CategoriesPlugin::groupByCategories);
        // @formatter:on

        launchResults.stream()
                .map(LaunchResults::getResults)
                .flatMap(Collection::stream)
                .sorted(comparingByTimeAsc())
                .forEach(categories::add);
        return categories;
    }

    /* default */ static void addCategoriesForResults(final List<LaunchResults> launchesResults) {
        launchesResults.forEach(launch -> {
            final List<Category> categories = launch.getExtra(CATEGORIES, Collections::emptyList);
            launch.getResults().forEach(result -> {
                final List<Category> resultCategories = result.getExtraBlock(CATEGORIES, new ArrayList<>());
                categories.forEach(category -> {
                    if (matches(result, category)) {
                        resultCategories.add(category);
                    }
                });
                if (resultCategories.isEmpty() && Status.FAILED.equals(result.getStatus())) {
                    result.getExtraBlock(CATEGORIES, new ArrayList<Category>()).add(FAILED_TESTS);
                }
                if (resultCategories.isEmpty() && Status.BROKEN.equals(result.getStatus())) {
                    result.getExtraBlock(CATEGORIES, new ArrayList<Category>()).add(BROKEN_TESTS);
                }
            });
        });
    }

    protected static List<TreeLayer> groupByCategories(final TestResult testResult) {
        final Set<String> categories = testResult
                .<List<Category>>getExtraBlock(CATEGORIES, new ArrayList<>())
                .stream()
                .map(Category::getName)
                .collect(Collectors.toSet());
        final TreeLayer categoriesLayer = new DefaultTreeLayer(categories);
        final TreeLayer messageLayer = new DefaultTreeLayer(testResult.getStatusMessage());
        return Arrays.asList(categoriesLayer, messageLayer);
    }

    static String stripAnsi(final String input) {
        return input == null ? null : input.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
    }

    @SuppressWarnings({"PMD.NPathComplexity", "CyclomaticComplexity"})
    public static boolean matches(final TestResult result, final Category category) {
        final String cleanMessage = stripAnsi(result.getStatusMessage());
        final String cleanTrace = stripAnsi(result.getStatusTrace());
        final boolean matchesStatus = category.getMatchedStatuses().isEmpty()
                                      || nonNull(result.getStatus())
                                         && category.getMatchedStatuses().contains(result.getStatus());
        final boolean matchesMessage = isNull(category.getMessageRegex())
                                       || nonNull(cleanMessage)
                                          && matches(cleanMessage, category.getMessageRegex());
        final boolean matchesTrace = isNull(category.getTraceRegex())
                                     || nonNull(cleanTrace)
                                        && matches(cleanTrace, category.getTraceRegex());
        final boolean matchesFlaky = isNull(category.getFlaky())
                                     || result.isFlaky() == category.getFlaky();
        return matchesStatus && matchesMessage && matchesTrace && matchesFlaky;
    }

    private static boolean matches(final String message, final String pattern) {
        return Pattern.compile(pattern, Pattern.DOTALL).matcher(message).matches();
    }

    protected static TreeWidgetItem toWidgetItem(final TestResultTreeGroup group) {
        return new TreeWidgetItem()
                .setUid(group.getUid())
                .setName(group.getName())
                .setStatistic(calculateStatisticByLeafs(group));
    }

    /**
     * Adds categories info to test results.
     */
    private static final class EnrichDataAggregator implements Aggregator2 {

        @Override
        public void aggregate(final Configuration configuration,
                              final List<LaunchResults> launchesResults,
                              final ReportStorage storage) {
            addCategoriesForResults(launchesResults);
        }
    }

    /**
     * Generates tree data.
     */
    private static final class JsonAggregator extends CommonJsonAggregator2 {

        JsonAggregator() {
            super(JSON_FILE_NAME);
        }

        @Override
        protected Tree<TestResult> getData(final List<LaunchResults> launches) {
            return CategoriesPlugin.getData(launches);
        }
    }

    /**
     * Generates export data.
     */
    private static final class CsvExportAggregator extends CommonCsvExportAggregator2<CsvExportCategory> {

        CsvExportAggregator() {
            super(CSV_FILE_NAME, CsvExportCategory.class);
        }

        @Override
        protected List<CsvExportCategory> getData(final List<LaunchResults> launchesResults) {
            final List<CsvExportCategory> exportLabels = new ArrayList<>();
            final Tree<TestResult> data = CategoriesPlugin.getData(launchesResults);
            final List<TreeWidgetItem> items = data.getChildren().stream()
                    .filter(TestResultTreeGroup.class::isInstance)
                    .map(TestResultTreeGroup.class::cast)
                    .map(CategoriesPlugin::toWidgetItem)
                    .sorted(Comparator.comparing(TreeWidgetItem::getStatistic, comparator()).reversed())
                    .collect(Collectors.toList());
            items.forEach(item -> exportLabels.add(new CsvExportCategory(item)));
            return exportLabels;
        }
    }

    /**
     * Generates widget data.
     */
    private static final class WidgetAggregator extends CommonJsonAggregator2 {

        WidgetAggregator() {
            super(Constants.WIDGETS_DIR, JSON_FILE_NAME);
        }

        @Override
        protected Object getData(final List<LaunchResults> launches) {
            final Tree<TestResult> data = CategoriesPlugin.getData(launches);
            final List<TreeWidgetItem> items = data.getChildren().stream()
                    .filter(TestResultTreeGroup.class::isInstance)
                    .map(TestResultTreeGroup.class::cast)
                    .map(CategoriesPlugin::toWidgetItem)
                    .sorted(Comparator.comparing(TreeWidgetItem::getStatistic, comparator()).reversed())
                    .limit(10)
                    .collect(Collectors.toList());
            return new TreeWidgetData().setItems(items).setTotal(data.getChildren().size());
        }
    }
}
