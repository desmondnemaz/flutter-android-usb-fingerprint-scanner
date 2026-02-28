package com.zaz.zazjni;

public class ZAZJni{
	public native int ZAZMatch2Fp(byte[] src,byte[] dst);
	static{
		System.loadLibrary("ZAAlg");
		
	}
	
} 