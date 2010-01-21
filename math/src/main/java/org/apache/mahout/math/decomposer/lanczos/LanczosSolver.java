/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.math.decomposer.lanczos;


import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Matrix;
import org.apache.mahout.math.PlusWithScaleFunction;
import org.apache.mahout.math.SparseRowMatrix;
import org.apache.mahout.math.UnaryFunction;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.matrix.DoubleMatrix1D;
import org.apache.mahout.math.matrix.DoubleMatrix2D;
import org.apache.mahout.math.matrix.impl.DenseDoubleMatrix2D;
import org.apache.mahout.math.matrix.linalg.EigenvalueDecomposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Simple implementation of the <a href="http://en.wikipedia.org/wiki/Lanczos_algorithm">Lanczos algorithm</a> for
 * finding eigenvalues of a symmetric matrix, applied to non-symmetric matrices by applying Matrix.timesSquared(vector)
 * as the "matrix-multiplication" method.</p>
 * <p>
 * To avoid floating point overflow problems which arise in power-methods like Lanczos, an initial pass is made
 * through the input matrix to
 * <ul>
 *   <li>generate a good starting seed vector by summing all the rows of the input matrix, and</li>
 *   <li>compute the trace(inputMatrix<sup>t</sup>*matrix)
 * </ul>
 * </p>
 * <p>
 * This latter value, being the sum of all of the singular values, is used to rescale the entire matrix, effectively
 * forcing the largest singular value to be strictly less than one, and transforming floating point <em>overflow</em>
 * problems into floating point <em>underflow</em> (ie, very small singular values will become invisible, as they
 * will appear to be zero and the algorithm will terminate).
 * </p>
 * <p>This implementation uses {@link org.apache.mahout.math.matrix.linalg.EigenvalueDecomposition} to do the
 * eigenvalue extraction from the small (desiredRank x desiredRank) tridiagonal matrix.  Numerical stability is
 * achieved via brute-force: re-orthogonalization against all previous eigenvectors is computed after every pass.
 * This can be made smarter if (when!) this proves to be a major bottleneck.  Of course, this step can be parallelized
 * as well.
 * </p>
 * <p>TODO: The input corpus is only accessed in a semi-random-access fashion in the getInitialVector method, which
 * if it were refactored to act as an iterator, this entire class would be parallelized by simply implementing
 * Matrix.timesSquared(Vector) as a Map-Reduce call.  
 */
public class LanczosSolver {
  /**
   * Logger for this class.
   */
  private static final Logger log = LoggerFactory.getLogger(LanczosSolver.class);

  public static double SAFE_MAX = 1e150;

  private static final double NANOS_IN_MILLI = 1e6;

  private double scaleFactor = 0;

  private static final class Scale implements UnaryFunction {
    private final double d;

    public Scale(double d) {
      this.d = d;
    }

    public double apply(double arg1) {
      return arg1 * d;
    }
  }

  public void solve(Matrix corpus,
                    int desiredRank,
                    Matrix eigenVectors,
                    List<Double> eigenValues) {
    log.info("Finding " + desiredRank + " singular vectors of matrix with " + corpus.numRows() + " rows, via Lanczos");
    Vector currentVector = getInitialVector(corpus);
    Vector previousVector = new DenseVector(currentVector.size());
    Matrix basis = new SparseRowMatrix(new int[]{desiredRank, corpus.numCols()});
    basis.assignRow(0, currentVector);
    double alpha = 0;
    double beta = 0;
    DoubleMatrix2D triDiag = new DenseDoubleMatrix2D(desiredRank, desiredRank);
    for (int i = 1; i < desiredRank; i++) {
      startTime(TimingSection.ITERATE);
      Vector nextVector = corpus.timesSquared(currentVector);
      log.info(i + " passes through the corpus so far...");
      nextVector.assign(new Scale(1 / scaleFactor));
      nextVector.assign(previousVector, new PlusWithScaleFunction(-beta));
      // now orthogonalize
      alpha = currentVector.dot(nextVector);
      nextVector.assign(currentVector, new PlusWithScaleFunction(-alpha));
      endTime(TimingSection.ITERATE);
      startTime(TimingSection.ORTHOGANLIZE);
      orthoganalizeAgainstAllButLast(nextVector, basis);
      endTime(TimingSection.ORTHOGANLIZE);
      // and normalize
      beta = nextVector.norm(2);
      if (outOfRange(beta) || outOfRange(alpha)) {
        log.warn("Lanczos parameters out of range: alpha = " + alpha + ", beta = " + beta + ".  Bailing out early!");
        break;
      }
      final double b = beta;
      nextVector.assign(new Scale(1 / b));
      basis.assignRow(i, nextVector);
      previousVector = currentVector;
      currentVector = nextVector;
      // save the projections and norms!
      triDiag.set(i - 1, i - 1, alpha);
      if (i < desiredRank - 1) {
        triDiag.set(i - 1, i, beta);
        triDiag.set(i, i - 1, beta);
      }
    }
    startTime(TimingSection.TRIDIAG_DECOMP);

    log.info("Lanczos iteration complete - now to diagonalize the tri-diagonal auxiliary matrix.");
    // at this point, have tridiag all filled out, and basis is all filled out, and orthonormalized
    EigenvalueDecomposition decomp = new EigenvalueDecomposition(triDiag);

    DoubleMatrix2D eigenVects = decomp.getV();
    DoubleMatrix1D eigenVals = decomp.getRealEigenvalues();
    endTime(TimingSection.TRIDIAG_DECOMP);
    startTime(TimingSection.FINAL_EIGEN_CREATE);

    for (int i = 0; i < basis.numRows() - 1; i++) {
      Vector realEigen = new DenseVector(corpus.numCols());
      // the eigenvectors live as columns of V, in reverse order.  Weird but true.
      DoubleMatrix1D ejCol = eigenVects.viewColumn(basis.numRows() - i - 1);
      for (int j = 0; j < ejCol.size(); j++) {
        double d = ejCol.getQuick(j);
        realEigen.assign(basis.getRow(j), new PlusWithScaleFunction(d));
      }
      realEigen = realEigen.normalize();
      eigenVectors.assignRow(i, realEigen);
      log.info("Eigenvector " + i + " found with eigenvalue " + eigenVals.get(i));
      eigenValues.add(eigenVals.get(i));
    }
    log.info("LanczosSolver finished.");
    endTime(TimingSection.FINAL_EIGEN_CREATE);
  }

  private static boolean outOfRange(double d) {
    return Double.isNaN(d) || d > SAFE_MAX || -d > SAFE_MAX;
  }

  private void orthoganalizeAgainstAllButLast(Vector nextVector, Matrix basis) {
    for (int i = 0; i < basis.numRows() - 1; i++) {
      double alpha = nextVector.dot(basis.getRow(i));
      nextVector.assign(basis.getRow(i), new PlusWithScaleFunction(-alpha));
    }
  }

  protected Vector getInitialVector(Matrix corpus) {
    Vector v = null;
    for (int i = 0; i < corpus.numRows(); i++) {
      Vector vector = corpus.getRow(i);
      if (vector == null || vector.getLengthSquared() == 0) continue;
      scaleFactor += vector.dot(vector);
      if (v == null) {
        v = new DenseVector(vector.size()).plus(vector);
      } else {
        v.assign(vector, new PlusWithScaleFunction(1));
      }
    }
    v.assign(new Scale(1d / v.norm(2)));
    return v;
  }

  private void startTime(TimingSection section) {
    startTimes.put(section, System.nanoTime());
  }

  private void endTime(TimingSection section) {
    if (!times.containsKey(section)) times.put(section, 0L);
    times.put(section, times.get(section) + (System.nanoTime() - startTimes.get(section)));
  }

  public double getTimeMillis(TimingSection section) {
    return ((double) times.get(section)) / NANOS_IN_MILLI;
  }

  public static enum TimingSection {
    ITERATE, ORTHOGANLIZE, TRIDIAG_DECOMP, FINAL_EIGEN_CREATE
  }

  private Map<TimingSection, Long> startTimes = new EnumMap<TimingSection, Long>(TimingSection.class);
  private Map<TimingSection, Long> times = new EnumMap<TimingSection, Long>(TimingSection.class);
}