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
package io.qameta.allure.packages;

import io.qameta.allure.CommonJsonAggregator2;
import io.qameta.allure.Constants;
import io.qameta.allure.core.LaunchResults;
import io.qameta.allure.entity.LabelName;
import io.qameta.allure.entity.TestResult;
import io.qameta.allure.tree.DefaultTreeLayer;
import io.qameta.allure.tree.TestResultGroupFactory;
import io.qameta.allure.tree.TestResultTree;
import io.qameta.allure.tree.TestResultTreeGroup;
import io.qameta.allure.tree.TestResultTreeLeaf;
import io.qameta.allure.tree.Tree;
import io.qameta.allure.tree.TreeLayer;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static io.qameta.allure.entity.TestResult.comparingByTimeAsc;

/**
 * The plugin adds packages tab to the report.
 *
 * @since 2.0
 */
public class PackagesPlugin extends CommonJsonAggregator2 {

    public PackagesPlugin() {
        super(Constants.DATA_DIR, "packages.json");
    }

    @Override
    public Tree<TestResult> getData(final List<LaunchResults> launchResults) {

        final Tree<TestResult> packages = new TestResultTree(
                "packages",
                this::groupByPackages,
                new TestResultGroupFactory(),
                this::createLeaf
        );

        launchResults.stream()
                .map(LaunchResults::getResults)
                .flatMap(Collection::stream)
                .sorted(comparingByTimeAsc())
                .forEach(packages::add);

        return collapseGroupsWithOnlyOneChild(packages);
    }

    protected List<TreeLayer> groupByPackages(final TestResult testResult) {
        return testResult.findOneLabel(LabelName.PACKAGE)
                .map(packageName -> Arrays.asList(packageName.split("\\.")))
                .orElseGet(Collections::emptyList)
                .stream()
                .map(DefaultTreeLayer::new)
                .collect(Collectors.toList());
    }

    protected Tree<TestResult> collapseGroupsWithOnlyOneChild(final Tree<TestResult> packages) {
        packages.getChildren().stream()
                .filter(TestResultTreeGroup.class::isInstance)
                .map(TestResultTreeGroup.class::cast)
                .forEach(this::collapseGroupsWithOnlyOneChild);
        return packages;
    }

    protected void collapseGroupsWithOnlyOneChild(final TestResultTreeGroup groupNode) {
        groupNode.getChildren().stream()
                .filter(TestResultTreeGroup.class::isInstance)
                .map(TestResultTreeGroup.class::cast)
                .forEach(this::collapseGroupsWithOnlyOneChild);

        final long count = groupNode.getChildren().stream()
                .filter(TestResultTreeGroup.class::isInstance)
                .count();

        if (groupNode.getChildren().size() == 1 && count == 1) {
            groupNode.getChildren().stream()
                    .filter(TestResultTreeGroup.class::isInstance)
                    .map(TestResultTreeGroup.class::cast)
                    .forEach(next -> {
                        final String name = getName(groupNode, next);
                        groupNode.setName(name);
                        groupNode.setUid(name);
                        groupNode.setChildren(next.getChildren());
                    });
        }
    }

    protected String getName(final TestResultTreeGroup parent, final TestResultTreeGroup child) {
        return String.format("%s.%s", parent.getName(), child.getName());
    }

    private TestResultTreeLeaf createLeaf(final TestResultTreeGroup parent, final TestResult testResult) {
        final String name = testResult
                .findOneLabel(LabelName.TEST_METHOD)
                .filter(method -> !method.isEmpty())
                .orElseGet(testResult::getName);
        return new TestResultTreeLeaf(
                parent.getUid(),
                name,
                testResult
        );
    }
}
