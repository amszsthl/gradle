/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

class DeferredTaskDefinitionIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile << '''
            class SomeTask extends DefaultTask {
                SomeTask() {
                    println("Create ${path}")
                }
            }
            class SomeOtherTask extends DefaultTask {
                SomeOtherTask() {
                    println("Create ${path}")
                }
            }
        '''
    }

    def "task is created and configured when included directly in task graph"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task2", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task3", SomeTask)
        '''

        when:
        run("task1")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        result.assertNotOutput(":task2")
        result.assertNotOutput(":task3")

        when:
        run("task2")

        then:
        outputContains("Create :task2")
        outputContains("Configure :task2")
        result.assertNotOutput(":task1")
        result.assertNotOutput(":task3")

        when:
        run("task3")

        then:
        outputContains("Create :task3")
        result.assertNotOutput(":task1")
        result.assertNotOutput(":task2")
    }

    def "task is created and configured when referenced as a task dependency"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task2", SomeTask) {
                println "Configure ${path}"
                dependsOn task1
            }
            tasks.create("other")
        '''

        when:
        run("other")

        then:
        result.assertNotOutput("task1")
        result.assertNotOutput("task2")

        when:
        run("task2")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Create :task2")
        outputContains("Configure :task2")
    }

    def "task is created and configured when referenced as task dependency via task provider"() {
        buildFile << '''
            def t1 = tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.create("task2", SomeTask) {
                println "Configure ${path}"
                dependsOn t1
            }
            tasks.create("other")
        '''

        when:
        run("other")

        then:
        result.assertNotOutput("task1")
        outputContains("Create :task2")
        outputContains("Configure :task2")

        when:
        run("task2")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Create :task2")
        outputContains("Configure :task2")
    }

    def "task is created and configured when referenced during configuration"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            // Eager
            tasks.create("task2", SomeTask) {
                println "Configure ${path}"
                dependsOn task1
            }
            tasks.create("other")
        '''

        when:
        run("other")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Create :task2")
        outputContains("Configure :task2")
    }

    def "task is created and configured eagerly when referenced using withType(type, action)"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task2", SomeOtherTask) {
                println "Configure ${path}"
            }
            tasks.create("other")
            tasks.withType(SomeTask) {
                println "Matched ${path}"
            }
        '''

        when:
        run("other")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Matched :task1")
        result.assertNotOutput("task2")
    }

    def "build logic can configure each task only when required"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task2", SomeOtherTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task3")
            tasks.configureEachLater {
                println "Received ${path}"
            }
            tasks.create("other") {
                dependsOn "task3"
            }
        '''

        when:
        run("other")

        then:
        outputContains("Received :other")
        outputContains("Received :task3")
        result.assertNotOutput("task1")
        result.assertNotOutput("task2")

        when:
        run("task1")

        then:
        outputContains("Received :other")
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Received :task1")
        result.assertNotOutput("task2")
        result.assertNotOutput("task3")
    }

    @Issue("https://github.com/gradle/gradle-native/issues/707")
    def "task is created and configured eagerly when referenced using all { action }"() {
        buildFile << """
            def configureCount = 0
            tasks.createLater("task1", SomeTask) {
                configureCount++
                println "Configure \${path} " + configureCount
            }
            
            def tasksAllCount = 0
            tasks.all {
                tasksAllCount++
                println "Action " + path + " " + tasksAllCount
            }
            
            gradle.buildFinished {
                assert configureCount == 1
                assert tasksAllCount == 2 // help + task1
            }
        """

        expect:
        succeeds("help")
        result.output.count("Create :task1") == 1
        result.output.count("Configure :task1") == 1
        result.output.count("Action :task1") == 1
    }

    def "build logic can configure each task of a given type only when required"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task2", SomeOtherTask) {
                println "Configure ${path}"
            }
            tasks.createLater("task3", SomeOtherTask)
            tasks.configureEachLater(SomeTask) {
                println "Received ${path}"
            }
            tasks.create("other") {
                dependsOn "task3"
            }
        '''

        when:
        run("other")

        then:
        result.assertNotOutput("Received")
        result.assertNotOutput("task1")
        result.assertNotOutput("task2")

        when:
        run("task1")

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        outputContains("Received :task1")
        result.assertNotOutput("task2")
    }

    @Issue("https://github.com/gradle/gradle/issues/5148")
    def "can get a task by name with a filtered collection"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            
            tasks.create("other") {
                dependsOn tasks.withType(SomeTask).getByName("task1")
            }
        '''

        when:
        run "other"

        then:
        outputContains("Create :task1")
    }

    def "fails to get a task by name when it does not match the filtered type"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            
            tasks.create("other") {
                dependsOn tasks.withType(SomeOtherTask).getByName("task1")
            }
        '''

        when:
        fails "other"

        then:
        outputDoesNotContain("Create :task1")
        outputDoesNotContain("Configure :task1")
        failure.assertHasCause("Task with name 'task1' not found")
    }

    def "fails to get a task by name when it does not match the collection filter"() {
        buildFile << '''
            tasks.createLater("task1", SomeTask) {
                println "Configure ${path}"
            }
            
            tasks.create("other") {
                dependsOn tasks.matching { it.name.contains("foo") }.getByName("task1")
            }
        '''

        when:
        fails "other"

        then:
        outputContains("Create :task1")
        outputContains("Configure :task1")
        failure.assertHasCause("Task with name 'task1' not found")
    }

    @Issue("https://github.com/gradle/gradle-native/issues/661")
    def "executes each configuration actions once when realizing a task"() {
        buildFile << '''
            def actionExecutionCount = [:].withDefault { 0 }

            class A extends DefaultTask {}

            tasks.configureEachLater(A) {
                actionExecutionCount.a1++
            }

            tasks.configureEachLater(A) {
                actionExecutionCount.a2++
            }

            def a = tasks.createLater("a", A) {
                actionExecutionCount.a3++
            }

            a.configure {
                actionExecutionCount.a4++
            }

            tasks.configureEachLater(A) {
                actionExecutionCount.a5++
            }

            a.configure {
                actionExecutionCount.a6++
            }

            task assertActionExecutionCount {
                dependsOn a
                doLast {
                    assert actionExecutionCount.size() == 6
                    assert actionExecutionCount.values().every { it == 1 }
                }
            }
        '''

        expect:
        succeeds 'assertActionExecutionCount'
    }

    @Issue("https://github.com/gradle/gradle-native/issues/662")
    def "runs the lazy configuration actions in the same order as the eager configuration actions"() {
        buildFile << '''
            def actionExecutionOrderForTaskA = []

            class A extends DefaultTask {}

            tasks.configureEachLater(A) {
                actionExecutionOrderForTaskA << "1"
            }

            tasks.configureEachLater(A) {
                actionExecutionOrderForTaskA << "2"
            }

            def a = tasks.createLater("a", A) {
                actionExecutionOrderForTaskA << "3"
            }

            a.configure {
                actionExecutionOrderForTaskA << "4"
            }

            tasks.configureEachLater(A) {
                actionExecutionOrderForTaskA << "5"
            }

            a.configure {
                actionExecutionOrderForTaskA << "6"
            }

            def actionExecutionOrderForTaskB = []

            class B extends DefaultTask {}

            tasks.withType(B) {
                actionExecutionOrderForTaskB << "1"
            }

            tasks.withType(B) {
                actionExecutionOrderForTaskB << "2"
            }

            def b = tasks.create("b", B) {
                actionExecutionOrderForTaskB << "3"
            }

            b.configure {
                actionExecutionOrderForTaskB << "4"
            }

            tasks.withType(B) {
                actionExecutionOrderForTaskB << "5"
            }

            b.configure {
                actionExecutionOrderForTaskB << "6"
            }

            task assertActionExecutionOrder {
                dependsOn a, b
                doLast {
                    assert actionExecutionOrderForTaskA.size() == 6
                    assert actionExecutionOrderForTaskA == actionExecutionOrderForTaskB
                }
            }
        '''

        expect:
        succeeds 'assertActionExecutionOrder'
    }

    def "can overwrite a lazy task creation with a eager task creation without executing any lazy rules"() {
        buildFile << '''
            class MyTask extends DefaultTask {}
            def myTask = tasks.createLater("myTask", SomeTask) {
                assert false, "This task is overwritten before been realized"
            }
            myTask.configure {
                assert false, "This task is overwritten before been realized"
            }

            tasks.create(name: "myTask", type: SomeOtherTask, overwrite: true) {
               println "Configure ${path}"
            }
        '''

        expect:
        succeeds "help"

        result.output.count("Create :myTask") == 1
        result.output.count("Configure :myTask") == 1
    }

    def "can overwrite a lazy task creation with a eager task and configure lazy task again"() {
        buildFile << '''
            class MyTask extends DefaultTask {}
            def myTask = tasks.createLater("myTask", SomeTask) {
                assert false, "This task is overwritten before been realized"
            }
            myTask.configure {
                assert false, "This task is overwritten before been realized"
            }

            tasks.create(name: "myTask", type: SomeOtherTask, overwrite: true) {
               println "Configure ${path}"
            }

            myTask.configure {
                assert false, "This task was overwritten with an eager task of another type"
            }
        '''

        expect:
        succeeds "help"

        result.output.count("Create :myTask") == 1
        result.output.count("Configure :myTask") == 1
    }

    def "executes configuration rules for a lazy task only once when explicitly realized before been replaced"() {
        buildFile << '''
            class MyTask extends DefaultTask {}
            def creationRuleExecutionCount = 0
            def myTask = tasks.createLater("myTask", SomeTask) {
               assert creationRuleExecutionCount++ == 0, "This task creation rule should only execute once."
            }
            def configurationRuleExecutionCount = 0
            myTask.configure {
                assert configurationRuleExecutionCount++ == 0, "This configuration rule should only execute once."
            }
            myTask.get()

            tasks.create(name: "myTask", type: SomeOtherTask, overwrite: true) {
               println "Configure ${path}"
            }
        '''

        expect:
        succeeds "help"

        result.output.count("Create :myTask") == 2
        result.output.count("Configure :myTask") == 1
    }

    def "executes configureEach rule for explicitly realized task and eager overwritten task"() {
        buildFile << '''
            class MyTask extends DefaultTask {}
            def configureEachRuleExecutionCount = 0
            tasks.configureEachLater(SomeTask) {
                configureEachRuleExecutionCount++
            }

            def myTask = tasks.createLater("myTask", SomeTask)
            myTask.get()

            tasks.create(name: "myTask", type: SomeTask, overwrite: true) {
               println "Configure ${path}"
            }

            assert configureEachRuleExecutionCount == 2, "The configureEach rule should execute for the manually realized lazy task as well as the overwritten eager task"
        '''

        expect:
        succeeds "help"

        result.output.count("Create :myTask") == 2
        result.output.count("Configure :myTask") == 1
    }

    def "executes configureEach rule only for eager overwritten task"() {
        buildFile << '''
            class MyTask extends DefaultTask {}
            def configureEachRuleExecutionCount = 0
            tasks.configureEachLater(SomeTask) {
                configureEachRuleExecutionCount++
            }

            def myTask = tasks.createLater("myTask", SomeTask)
            
            tasks.create(name: "myTask", type: SomeTask, overwrite: true) {
               println "Configure ${path}"
            }

            assert configureEachRuleExecutionCount == 1, "The configureEach rule should execute only for the overwritten eager task"
        '''

        expect:
        succeeds "help"

        result.output.count("Create :myTask") == 1
        result.output.count("Configure :myTask") == 1
    }

    def "configure rule can create additional tasks"() {
        buildFile << '''
            def fooTasks = tasks.withType(SomeTask)
            
            tasks.createLater('foo', SomeTask) {
                dependsOn tasks.createLater('bar')
            }
            
            task('baz') {
                dependsOn fooTasks
            }
        '''

        expect:
        succeeds "baz"
    }
}
