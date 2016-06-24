package org.jetbrains.bio.jni;

import org.jetbrains.bio.viktor.BalancedSumKt;
import org.jetbrains.bio.viktor.StridedVectorKt;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(value = 2, jvmArgsPrepend = "-Djava.library.path=./build/libs")
public class SumBenchmark {
	@Param({"1000", "10000", "100000"})
	int arraySize;
	double[] values;

	@Setup
	public void generateData() {
		Loader.INSTANCE.ensureLoaded();

		final Random random = new Random();
		values = random.doubles(arraySize).toArray();
	}

	@Benchmark
	public double simpleSum() {
		double res = 0.;
		for (int i = 0; i < arraySize; i++) {
			res += values[i];
		}
		return res;
	}

	@Benchmark
	public double javaSum() {
		return BalancedSumKt.balancedSum(StridedVectorKt.asStrided(values, 0, arraySize));
	}

	@Benchmark
	public double nativeSum() {
		return NativeSpeedups.INSTANCE.sum(values, 0, arraySize);
	}

}
