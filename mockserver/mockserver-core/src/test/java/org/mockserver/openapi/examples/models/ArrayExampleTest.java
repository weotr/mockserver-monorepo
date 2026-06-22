/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples.models;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ArrayExampleTest {

    @Test
    public void shouldCommaSeparateItemsInOrder() {
        ArrayExample array = new ArrayExample();
        array.add(new StringExample("a"));
        array.add(new StringExample("b"));
        array.add(new StringExample("c"));

        // regression: the separator used to be appended after the wrong element -> "[ab,c]"
        assertThat(array.asString(), is("[a,b,c]"));
    }

    @Test
    public void shouldRenderSingleItemWithoutSeparator() {
        ArrayExample array = new ArrayExample();
        array.add(new StringExample("a"));

        assertThat(array.asString(), is("[a]"));
    }

    @Test
    public void shouldRenderEmptyArray() {
        assertThat(new ArrayExample().asString(), is("[]"));
    }

    @Test
    public void shouldCommaSeparateMixedItems() {
        ArrayExample array = new ArrayExample();
        array.add(new IntegerExample(1));
        array.add(new IntegerExample(2));

        assertThat(array.asString(), is("[1,2]"));
    }
}
