/*
 * Copyright Myrrix Ltd
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

package net.myrrix.client.eval;

import java.io.Serializable;

final class EvaluationResultImpl implements EvaluationResult, Serializable {

  private final double score;

  EvaluationResultImpl(double score) {
    this.score = score;
  }

  @Override
  public double getScore() {
    return score;
  }

  @Override
  public String toString() {
    return Double.toString(score);
  }

}
