// Copyright 2014 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package go;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Seq is a sequence of machine-dependent encoded values.
// Used by automatically generated language bindings to talk to Go.
public class Seq {
	static {
		// Look for the shim class auto-generated by gomobile bind.
		// Its only purpose is to call System.loadLibrary.
		try {
			Class.forName("go.LoadJNI");
		} catch (ClassNotFoundException e) {
			// Ignore, assume the user will load JNI for it.
			Log.w("GoSeq", "LoadJNI class not found");
		}

		try {
			// TODO(hyangah): check proguard rule.
			Application appl = (Application)Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null);
			Context ctx = appl.getApplicationContext();
			setContext(ctx);

		} catch (Exception e) {
			Log.w("GoSeq", "Global context not found:" + e);
		}

		initSeq();
		new Thread("GoSeq") {
			public void run() { Seq.receive(); }
		}.start();
	}

	@SuppressWarnings("UnusedDeclaration")
	private long memptr; // holds C-allocated pointer

	public Seq() {
		ensure(64);
	}

	static native void setContext(Context ctx);

	// Ensure that at least size bytes can be written to the Seq.
	// Any existing data in the buffer is preserved.
	public native void ensure(int size);

	// Moves the internal buffer offset back to zero.
	// Length and contents are maintained. Data can be read after a reset.
	public native void resetOffset();

	public native void log(String label);

	public native boolean readBool();
	public native byte readInt8();
	public native short readInt16();
	public native int readInt32();
	public native long readInt64();
	public long readInt() { return readInt64(); }

	public native float readFloat32();
	public native double readFloat64();
	public native String readUTF16();
	public String readString() { return readUTF16(); }
	public native byte[] readByteArray();

	public native void writeBool(boolean v);
	public native void writeInt8(byte v);
	public native void writeInt16(short v);
	public native void writeInt32(int v);
	public native void writeInt64(long v);
	public void writeInt(long v) { writeInt64(v); }

	public native void writeFloat32(float v);
	public native void writeFloat64(double v);
	public native void writeUTF16(String v);
	public void writeString(String v) { writeUTF16(v); }
	public native void writeByteArray(byte[] v);

	public void writeRef(Ref ref) {
		tracker.inc(ref);
		writeInt32(ref.refnum);
	}

	public Ref readRef() {
		int refnum = readInt32();
		return tracker.get(refnum);
	}

	static native void initSeq();

	// Informs the Go ref tracker that Java is done with this ref.
	static native void destroyRef(int refnum);

	// createRef creates a Ref to a Java object.
	public static Ref createRef(Seq.Object o) {
		return tracker.createRef(o);
	}

	// sends a function invocation request to Go.
	//
	// Blocks until the function completes.
	// If the request is for a method, the first element in src is
	// a Ref to the receiver.
	public static native void send(String descriptor, int code, Seq src, Seq dst);

	// recv returns the next request from Go for a Java call.
	static native void recv(Seq in, Receive params);

	// recvRes sends the result of a Java call back to Go.
	static native void recvRes(int handle, Seq out);

	static final class Receive {
		int refnum;
		int code;
		int handle;
	}

	protected void finalize() throws Throwable {
		super.finalize();
		free();
	}
	private native void free();

	private static final ExecutorService receivePool = Executors.newCachedThreadPool();

	// receive listens for callback requests from Go, invokes them on a thread
	// pool and sends the responses.
	public static void receive() {
		Seq.Receive params = new Seq.Receive();
		while (true) {
			final Seq in = new Seq();
			Seq.recv(in, params);

			final int code = params.code;
			final int handle = params.handle;
			final int refnum = params.refnum;

			if (code == -1) {
				// Special signal from seq.FinalizeRef.
				tracker.dec(refnum);
				Seq out = new Seq();
				Seq.recvRes(handle, out);
				continue;
			}

			receivePool.execute(new Runnable() {
				public void run() {
					Ref r = tracker.get(refnum);
					Seq out = new Seq();
					r.obj.call(code, in, out);
					Seq.recvRes(handle, out);
				}
			});
		}
	}

	// An Object is a Java object that matches a Go object.
	// The implementation of the object may be in either Java or Go,
	// with a proxy instance in the other language passing calls
	// through to the other language.
	//
	// Don't implement an Object directly. Instead, look for the
	// generated abstract Stub.
	public interface Object {
		public Ref ref();
		public void call(int code, Seq in, Seq out);
	}

	// A Ref is an object tagged with an integer for passing back and
	// forth across the language boundary.
	//
	// A Ref may represent either an instance of a Java Object subclass,
	// or an instance of a Go object. The explicit allocation of a Ref
	// is used to pin Go object instances when they are passed to Java.
	// The Go Seq library maintains a reference to the instance in a map
	// keyed by the Ref number. When the JVM calls finalize, we ask Go
	// to clear the entry in the map.
	public static final class Ref {
		// refnum < 0: Go object tracked by Java
		// refnum > 0: Java object tracked by Go
		int refnum;

		int refcnt;  // for Java obj: track how many times sent to Go.

		public Seq.Object obj;  // for Java obj: pointers to the Java obj.

		private Ref(int refnum, Seq.Object o) {
			this.refnum = refnum;
			this.refcnt = 0;
			this.obj = o;
		}

		@Override
		protected void finalize() throws Throwable {
			if (refnum < 0) {
				// Go object: signal Go to decrement the reference count.
				Seq.destroyRef(refnum);
			}
			super.finalize();
		}
	}

	static final RefTracker tracker = new RefTracker();

	static final class RefTracker {
		// Next Java object reference number.
		//
		// Reference numbers are positive for Java objects,
		// and start, arbitrarily at a different offset to Go
		// to make debugging by reading Seq hex a little easier.
		private int next = 42; // next Java object ref

		// Java objects that have been passed to Go. refnum -> Ref
		// The Ref obj field is non-null.
		// This map pins Java objects so they don't get GCed while the
		// only reference to them is held by Go code.
		private SparseArray<Ref> javaObjs = new SparseArray<Ref>();

		// inc increments the reference count of a Java object when it
		// is sent to Go.
		synchronized void inc(Ref ref) {
			int refnum = ref.refnum;
			if (refnum <= 0) {
				// We don't keep track of the Go object.
				return;
			}
			// Count Java objects passed to Go.
			if (ref.refcnt == Integer.MAX_VALUE) {
				throw new RuntimeException("refnum " + refnum + " overflow");
			}
			ref.refcnt++;
			Ref obj = javaObjs.get(refnum);
			if (obj == null) {
				javaObjs.put(refnum, ref);
			}
		}

		// dec decrements the reference count of a Java object when
		// Go signals a corresponding proxy object is finalized.
		// If the count reaches zero, the Java object is removed
		// from the javaObjs map.
		synchronized void dec(int refnum) {
			if (refnum <= 0) {
				// We don't keep track of the Go object.
				// This must not happen.
				Log.wtf("GoSeq", "dec request for Go object "+ refnum);
				return;
			}
			// Java objects are removed on request of Go.
			Ref obj = javaObjs.get(refnum);
			if (obj == null) {
				throw new RuntimeException("referenced Java object is not found: refnum="+refnum);
			}
			obj.refcnt--;
			if (obj.refcnt <= 0) {
				javaObjs.remove(refnum);
			}
		}

		synchronized Ref createRef(Seq.Object o) {
			// TODO(crawshaw): use single Ref for null.
			if (next == Integer.MAX_VALUE) {
				throw new RuntimeException("createRef overflow for " + o);
			}
			int refnum = next++;
			Ref ref = new Ref(refnum, o);
			javaObjs.put(refnum, ref);
			return ref;
		}

		// get returns an existing Ref to either a Java or Go object.
		// It may be the first time we have seen the Go object.
		//
		// TODO(crawshaw): We could cut down allocations for frequently
		// sent Go objects by maintaining a map to weak references. This
		// however, would require allocating two objects per reference
		// instead of one. It also introduces weak references, the bane
		// of any Java debugging session.
		//
		// When we have real code, examine the tradeoffs.
		synchronized Ref get(int refnum) {
			if (refnum > 0) {
				Ref ref = javaObjs.get(refnum);
				if (ref == null) {
					throw new RuntimeException("unknown java Ref: "+refnum);
				}
				return ref;
			} else {
				// Go object.
				return new Ref(refnum, null);
			}
		}
	}
}
