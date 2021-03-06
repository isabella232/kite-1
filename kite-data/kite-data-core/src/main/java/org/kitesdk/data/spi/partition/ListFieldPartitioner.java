/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.data.spi.partition;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import javax.annotation.concurrent.Immutable;
import org.kitesdk.data.spi.FieldPartitioner;
import org.kitesdk.data.spi.Predicates;

@edu.umd.cs.findbugs.annotations.SuppressWarnings(
    value="SE_COMPARATOR_SHOULD_BE_SERIALIZABLE",
    justification="Implement if we intend to use in Serializable objects "
        + " (e.g., TreeMaps) and use java serialization.")
@Immutable
public class ListFieldPartitioner<S> extends FieldPartitioner<S, Integer> {

  private final List<Set<S>> values;

  public ListFieldPartitioner(String name, List<Set<S>> values, Class<S> sourceType) {
    super(name, name, sourceType, Integer.class, cardinality(values));
    this.values = values;
  }

  private static <S> int cardinality(List<Set<S>> values) {
    return values.size(); // the number of sets
  }

  @Override
  public Integer apply(S value) {
    // find the index of the set to which value belongs
    for (int i = 0; i < values.size(); i++) {
      if (values.get(i).contains(value)) {
        return i;
      }
    }

    throw new IllegalArgumentException(value + " is not in set");
  }

  @Override
  @Deprecated
  public Integer valueFromString(String stringValue) {
    return Integer.parseInt(stringValue);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Predicate<Integer> project(Predicate<S> predicate) {
    if (predicate instanceof Predicates.Exists) {
      return Predicates.exists();
    } else if (predicate instanceof Predicates.In) {
      return ((Predicates.In<S>) predicate).transform(this);
    } else if (predicate instanceof Range) {
      Range range = (Range) predicate;
      Set<Integer> possibleValues = Sets.newHashSet();
      for (int i = 0; i < values.size(); i += 1) {
        Set<S> items = values.get(i);
        if (items.size() == Integer.MAX_VALUE) {
          // items may not be finite, do not consider each item
          possibleValues.add(i);
        } else {
          // check each item in the set
          for (S item : items) {
            if (range.contains((Comparable) item)) {
              possibleValues.add(i);
              break; // no need to test additional items in this set
            }
          }
        }
      }
      if (!possibleValues.isEmpty()) {
        return Predicates.in(possibleValues);
      }
    }
    return null;
  }

  @Override
  public Predicate<Integer> projectStrict(Predicate<S> predicate) {
    if (predicate instanceof Predicates.Exists) {
      return Predicates.exists();
    } else if (predicate instanceof Predicates.In ||
        predicate instanceof Range) {
      Set<Integer> possibleValues = Sets.newHashSet();
      for (int i = 0; i < values.size(); i += 1) {
        Set<S> items = values.get(i);
        // only check items if the set is finite
        if (items.size() != Integer.MAX_VALUE) {
          boolean matchedAll = true;
          for (S entry : items) {
            matchedAll = matchedAll && predicate.apply(entry);
          }
          if (matchedAll) {
            possibleValues.add(i);
          }
        }
      }
      if (!possibleValues.isEmpty()) {
        return Predicates.in(possibleValues);
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !getClass().equals(o.getClass())) {
      return false;
    }
    ListFieldPartitioner that = (ListFieldPartitioner) o;
    return Objects.equal(this.getName(), that.getName()) &&
        Objects.equal(this.values, that.values);
  }

  @Override
  public int compare(Integer o1, Integer o2) {
    return o1.compareTo(o2);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getName(), values);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this).add("name", getName())
        .add("values", values).toString();
  }
}
