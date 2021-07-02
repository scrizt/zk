/* B96_ZK_4869Test.java

	Purpose:
		
	Description:
		
	History:
		Wed Jun 30 11:23:33 CST 2021, Created by jameschu

Copyright (C) 2021 Potix Corporation. All Rights Reserved.
*/
package org.zkoss.zktest.zats.test2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

import org.zkoss.zktest.zats.WebDriverTestCase;
import org.zkoss.zktest.zats.ztl.JQuery;

/**
 * @author jameschu
 */
public class B96_ZK_4869Test extends WebDriverTestCase {
	@Test
	public void test() {
		connect();
		JQuery jqWest = jq(".z-west");
		JQuery jqEast = jq(".z-east");
		int widthOfWest = jqWest.width();
		int widthOfEast = jqEast.width();
		JQuery jqBtn = jq("@button");
		for (int i = 0; i < 5; i++) {
			click(jqBtn);
			waitResponse();
			assertEquals(widthOfWest, jqWest.width());
			assertEquals(widthOfEast, jqEast.width());
		}
	}
}
