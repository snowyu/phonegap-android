// Taken from Android tutorials
/*
 * PhoneGap is available under *either* the terms of the modified BSD license *or* the
 * MIT License (2008). See http://opensource.org/licenses/alphabetical for full text.
 * 
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 * Copyright (c) 2011, Giant Leap Technologies AS
 */
/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.phonegap;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;
import android.webkit.WebView;

/**
 * An implementation of {@link ContactAccessor} that uses current Contacts API.
 * This class should be used on Eclair or beyond, but would not work on any earlier
 * release of Android.  As a matter of fact, it could not even be loaded.
 * <p>
 * This implementation has several advantages:
 * <ul>
 * <li>It sees contacts from multiple accounts.
 * <li>It works with aggregated contacts. So for example, if the contact is the result
 * of aggregation of two raw contacts from different accounts, it may return the name from
 * one and the phone number from the other.
 * <li>It is efficient because it uses the more efficient current API.
 * <li>Not obvious in this particular example, but it has access to new kinds
 * of data available exclusively through the new APIs. Exercise for the reader: add support
 * for nickname (see {@link android.provider.ContactsContract.CommonDataKinds.Nickname}) or
 * social status updates (see {@link android.provider.ContactsContract.StatusUpdates}).
 * </ul>
 */
public class ContactAccessorSdk5 extends ContactAccessor {

	/**
	 * Keep the photo size under the 1 MB blog limit.
	 */
	private static final long MAX_PHOTO_SIZE = 1048576;
	
	private static final String EMAIL_REGEXP = ".+@.+\\.+.+";  /* <anything>@<anything>.<anything>*/

	/**
	 * A static map that converts the JavaScript property name to Android database column name.
	 */
    private static final Map<String, String> dbMap = new HashMap<String, String>();
    static {
    	dbMap.put("id", ContactsContract.Contacts._ID);
    	dbMap.put("displayName", ContactsContract.Contacts.DISPLAY_NAME);
    	dbMap.put("name", ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME);
    	dbMap.put("name.formatted", ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME);
    	dbMap.put("name.familyName", ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
    	dbMap.put("name.givenName", ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
    	dbMap.put("name.middleName", ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME);
    	dbMap.put("name.honorificPrefix", ContactsContract.CommonDataKinds.StructuredName.PREFIX);
    	dbMap.put("name.honorificSuffix", ContactsContract.CommonDataKinds.StructuredName.SUFFIX);
    	dbMap.put("nickname", ContactsContract.CommonDataKinds.Nickname.NAME);
    	dbMap.put("phoneNumbers", ContactsContract.CommonDataKinds.Phone.NUMBER);
    	dbMap.put("phoneNumbers.value", ContactsContract.CommonDataKinds.Phone.NUMBER);
    	dbMap.put("emails", ContactsContract.CommonDataKinds.Email.DATA);
    	dbMap.put("emails.value", ContactsContract.CommonDataKinds.Email.DATA);
    	dbMap.put("addresses", ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS);
    	dbMap.put("addresses.formatted", ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS);
    	dbMap.put("addresses.streetAddress", ContactsContract.CommonDataKinds.StructuredPostal.STREET);
    	dbMap.put("addresses.locality", ContactsContract.CommonDataKinds.StructuredPostal.CITY);
    	dbMap.put("addresses.region", ContactsContract.CommonDataKinds.StructuredPostal.REGION);
    	dbMap.put("addresses.postalCode", ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE);
    	dbMap.put("addresses.country", ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY);
    	dbMap.put("ims", ContactsContract.CommonDataKinds.Im.DATA);
    	dbMap.put("ims.value", ContactsContract.CommonDataKinds.Im.DATA);
    	dbMap.put("organizations", ContactsContract.CommonDataKinds.Organization.COMPANY);
    	dbMap.put("organizations.name", ContactsContract.CommonDataKinds.Organization.COMPANY);
    	dbMap.put("organizations.department", ContactsContract.CommonDataKinds.Organization.DEPARTMENT);
    	dbMap.put("organizations.title", ContactsContract.CommonDataKinds.Organization.TITLE);
    	//dbMap.put("revision", null);
    	dbMap.put("birthday", ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE);
    	dbMap.put("note", ContactsContract.CommonDataKinds.Note.NOTE);
    	dbMap.put("photos.value", ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
    	//dbMap.put("categories.value", null);
    	dbMap.put("urls", ContactsContract.CommonDataKinds.Website.URL);
    	dbMap.put("urls.value", ContactsContract.CommonDataKinds.Website.URL);
    	//dbMap.put("timezone", null);
    }

    /**
     * Create an contact accessor.
     */
    public ContactAccessorSdk5(WebView view, Activity app) {
		mApp = app;
		mView = view;
	}
	
	/** 
	 * This method takes the fields required and search options in order to produce an 
	 * array of contacts that matches the criteria provided.
	 * @param fields an array of items to be used as search criteria
	 * @param options that can be applied to contact searching
	 * @return an array of contacts 
	 */
	@Override
	public JSONArray search(JSONArray fields, JSONObject options) {
		long totalEnd;
		long totalStart = System.currentTimeMillis();

		// Get the find options
		String searchTerm = "";
		int limit = Integer.MAX_VALUE;
		boolean multiple = true;
		
		if (options != null) {
			searchTerm = options.optString("filter");
			if (searchTerm.length()==0) {
				searchTerm = "%";
			}
			else {
				searchTerm = "%" + searchTerm + "%";
			}
			try {
				multiple = options.getBoolean("multiple");
				if (!multiple) {
					limit = 1;
				}
			} catch (JSONException e) {
				// Multiple was not specified so we assume the default is true.
			}
		}
		else {
			searchTerm = "%";
		}
		
		//Log.d(LOG_TAG, "Search Term = " + searchTerm);
		//Log.d(LOG_TAG, "Field Length = " + fields.length());
		//Log.d(LOG_TAG, "Fields = " + fields.toString());

		// Loop through the fields the user provided to see what data should be returned.
		HashMap<String,Boolean> populate = buildPopulationSet(fields);
		
		// Build the ugly where clause and where arguments for one big query.
		WhereOptions whereOptions = buildWhereClause(fields, searchTerm);
			
		// Get all the id's where the search term matches the fields passed in.
		Cursor idCursor = mApp.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
				new String[] { ContactsContract.Data.CONTACT_ID },
				whereOptions.getWhere(),
				whereOptions.getWhereArgs(),
				ContactsContract.Data.CONTACT_ID + " ASC");				

		// Create a set of unique ids
		//Log.d(LOG_TAG, "ID cursor query returns = " + idCursor.getCount());
		Set<String> contactIds = new HashSet<String>();
		while (idCursor.moveToNext()) {
			contactIds.add(idCursor.getString(idCursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)));
		}
		idCursor.close();
		
		// Build a query that only looks at ids
		WhereOptions idOptions = buildIdClause(contactIds, searchTerm);
		
		// Do the id query
		Cursor c = mApp.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
				null,
				idOptions.getWhere(),
				idOptions.getWhereArgs(),
				ContactsContract.Data.CONTACT_ID + " ASC");				
		
		
		Log.d(LOG_TAG, "Cursor length = " + c.getCount());
		
		String contactId = "";
		String rawId = "";
		String oldContactId = "";
		boolean newContact = true;
		String mimetype = "";

		JSONArray contacts = new JSONArray();
		JSONObject contact = new JSONObject();
		JSONArray organizations = new JSONArray();
		JSONArray addresses = new JSONArray();
		JSONArray phones = new JSONArray();
		JSONArray emails = new JSONArray();
		JSONArray ims = new JSONArray();
		JSONArray websites = new JSONArray();
		JSONArray photos = new JSONArray();			
		
		if (c.getCount() > 0) {
			while (c.moveToNext() && (contacts.length() <= (limit-1))) {					
				try {
					contactId = c.getString(c.getColumnIndex(ContactsContract.Data.CONTACT_ID));
					rawId = c.getString(c.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID));				
					
					// If we are in the first row set the oldContactId
					if (c.getPosition() == 0) {
						oldContactId = contactId;
					}
					
					// When the contact ID changes we need to push the Contact object 
					// to the array of contacts and create new objects.
					if (!oldContactId.equals(contactId)) {
						// Populate the Contact object with it's arrays
						// and push the contact into the contacts array
						contacts.put(populateContact(contact, organizations, addresses, phones,
								emails, ims, websites, photos));
						
						// Clean up the objects
						contact = new JSONObject();
						organizations = new JSONArray();
						addresses = new JSONArray();
						phones = new JSONArray();
						emails = new JSONArray();
						ims = new JSONArray();
						websites = new JSONArray();
						photos = new JSONArray();
						
						// Set newContact to true as we are starting to populate a new contact
						newContact = true;
					}
					
					// When we detect a new contact set the ID and display name.
					// These fields are available in every row in the result set returned.
					if (newContact) {
						newContact = false;
						contact.put("id", contactId);
						contact.put("rawId", rawId);
						contact.put("displayName", c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)));
					}
					
					// Grab the mimetype of the current row as it will be used in a lot of comparisons
					mimetype = c.getString(c.getColumnIndex(ContactsContract.Data.MIMETYPE));
					
					if (mimetype.equals(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) 
							&& isRequired("name",populate)) {
						contact.put("name", nameQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) 
							&& isRequired("phoneNumbers",populate)) {
						phones.put(phoneQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) 
							&& isRequired("emails",populate)) {
						emails.put(emailQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE) 
							&& isRequired("addresses",populate)) {
						addresses.put(addressQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) 
							&& isRequired("organizations",populate)) {
						organizations.put(organizationQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE) 
							&& isRequired("ims",populate)) {
						ims.put(imQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE) 
							&& isRequired("note",populate)) {
						contact.put("note",c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE) 
							&& isRequired("nickname",populate)) {
						contact.put("nickname",c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Nickname.NAME)));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE) 
							&& isRequired("urls",populate)) {
						websites.put(websiteQuery(c));
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)) {
						if (ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY == c.getInt(c.getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE)) 
								&& isRequired("birthday",populate)) {
							contact.put("birthday", c.getString(c.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)));
						}
					}
					else if (mimetype.equals(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE) 
							&& isRequired("photos",populate)) {
						JSONObject vPhoto = photoQuery(c, contactId);
						if (vPhoto != null) photos.put(photoQuery(c, contactId));
					}
				}
				catch (JSONException e) {
					Log.e(LOG_TAG, e.getMessage(),e);
				}
				
				// Set the old contact ID 
				oldContactId = contactId;			
			}
	
			// Push the last contact into the contacts array
			if (contacts.length() < limit) {
				contacts.put(populateContact(contact, organizations, addresses, phones,
						emails, ims, websites, photos));
			}
		}
		c.close();
		
		
		totalEnd = System.currentTimeMillis();
		Log.d(LOG_TAG,"Total time = " + (totalEnd-totalStart));
		return contacts;
	}

	/**
	 * Builds a where clause all all the ids passed into the method
	 * @param contactIds a set of unique contact ids
	 * @param searchTerm what to search for
	 * @return an object containing the selection and selection args
	 */
	private WhereOptions buildIdClause(Set<String> contactIds, String searchTerm) {		
		WhereOptions options = new WhereOptions();
		
		// If the user is searching for every contact then short circuit the method
		// and return a shorter where clause to be searched.
		if (searchTerm.equals("%")) {
			options.setWhere("(" + ContactsContract.Data.CONTACT_ID + " LIKE ? )");
			options.setWhereArgs(new String[] {searchTerm});
			return options;
		}

		// This clause means that there are specific ID's to be populated
		Iterator<String> it = contactIds.iterator();
		StringBuffer buffer = new StringBuffer("(");
		
		while (it.hasNext()) {
			buffer.append("'" + it.next() + "'");
			if (it.hasNext()) {
				buffer.append(",");
			}
		}
		buffer.append(")");
		
		options.setWhere(ContactsContract.Data.CONTACT_ID + " IN " + buffer.toString());
		options.setWhereArgs(null);		
				
		return options;
	}

	/**
	 * Create a new contact using a JSONObject to hold all the data. 
	 * @param contact 
	 * @param organizations array of organizations
	 * @param addresses array of addresses
	 * @param phones array of phones
	 * @param emails array of emails
	 * @param ims array of instant messenger addresses
	 * @param websites array of websites
	 * @param photos 
	 * @return
	 */
	private JSONObject populateContact(JSONObject contact, JSONArray organizations,
			JSONArray addresses, JSONArray phones, JSONArray emails,
			JSONArray ims, JSONArray websites, JSONArray photos) {
		try {
			contact.put("organizations", organizations);
			contact.put("addresses", addresses);
			contact.put("phoneNumbers", phones);
			contact.put("emails", emails);
			contact.put("ims", ims);
			contact.put("websites", websites);
			contact.put("photos", photos);
		}
		catch (JSONException e) {
			Log.e(LOG_TAG,e.getMessage(),e);
		}
		return contact;
	}

	/**
	 * Take the search criteria passed into the method and create a SQL WHERE clause.
	 * @param fields the properties to search against
	 * @param searchTerm the string to search for
	 * @return an object containing the selection and selection args
	 */
	private WhereOptions buildWhereClause(JSONArray fields, String searchTerm) {

		ArrayList<String> where = new ArrayList<String>();
		ArrayList<String> whereArgs = new ArrayList<String>();
		
		WhereOptions options = new WhereOptions();

		/*
		 * Special case for when the user wants all the contacts
		 */
		if ("%".equals(searchTerm)) {
			options.setWhere("(" + ContactsContract.Contacts.DISPLAY_NAME + " LIKE ? )");
			options.setWhereArgs(new String[] {searchTerm});
			return options;
		}		
		
		String key;
		try {
			//Log.d(LOG_TAG, "How many fields do we have = " + fields.length());
			for (int i=0; i<fields.length(); i++) {
				key = fields.getString(i);

				if (key.startsWith("displayName")) {
					where.add("(" + dbMap.get(key) + " LIKE ? )");
					whereArgs.add(searchTerm);
				}
				else if (key.startsWith("name")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("nickname")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("phoneNumbers")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("emails")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("addresses")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("ims")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("organizations")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
				}
//				else if (key.startsWith("birthday")) {
//					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
//							+ ContactsContract.Data.MIMETYPE + " = ? )");									
//				}
				else if (key.startsWith("note")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE);
				}
				else if (key.startsWith("urls")) {
					where.add("(" + dbMap.get(key) + " LIKE ? AND " 
							+ ContactsContract.Data.MIMETYPE + " = ? )");				
					whereArgs.add(searchTerm);
					whereArgs.add(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE);
				}
			}
		}
		catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}

		// Creating the where string
		StringBuffer selection = new StringBuffer();
		for (int i=0; i<where.size(); i++) {
			selection.append(where.get(i));
			if (i != (where.size()-1)) {
				selection.append(" OR ");
			}
		}
		options.setWhere(selection.toString());

		// Creating the where args array
		String[] selectionArgs = new String[whereArgs.size()];
		for (int i=0; i<whereArgs.size(); i++) {
			selectionArgs[i] = whereArgs.get(i);
		}
		options.setWhereArgs(selectionArgs);
		
		return options;
	}

	/**
	 * Create a ContactOrganization JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactOrganization
	 */
	private JSONObject organizationQuery(Cursor cursor) {
		JSONObject organization = new JSONObject();
		try {
			organization.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization._ID)));
			organization.put("department", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.DEPARTMENT)));
			organization.put("name", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)));
			organization.put("title", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE)));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return organization;
	}

	/**
	 * Create a ContactAddress JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactAddress
	 */
	private JSONObject addressQuery(Cursor cursor) {
		JSONObject address = new JSONObject();
		try {
			address.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal._ID)));
			address.put("formatted", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)));
			address.put("streetAddress", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)));
			address.put("locality", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)));
			address.put("region", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION)));
			address.put("postalCode", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)));
			address.put("country", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return address;
	}

	/**
	 * Create a ContactName JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactName
	 */
	private JSONObject nameQuery(Cursor cursor) {
		JSONObject contactName = new JSONObject();
		try {
			String familyName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME));
			String givenName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME));
			String middleName = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME));
			String honorificPrefix = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.PREFIX));
			String honorificSuffix = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.SUFFIX));

			// Create the formatted name
			StringBuffer formatted = new StringBuffer("");
			if (honorificPrefix != null) { formatted.append(honorificPrefix + " "); }
			if (givenName != null) { formatted.append(givenName + " "); }
			if (middleName != null) { formatted.append(middleName + " "); }
			if (familyName != null) { formatted.append(familyName + " "); }
			if (honorificSuffix != null) { formatted.append(honorificSuffix + " "); }
			
			contactName.put("familyName", familyName);
			contactName.put("givenName", givenName);
			contactName.put("middleName", middleName);
			contactName.put("honorificPrefix", honorificPrefix);
			contactName.put("honorificSuffix", honorificSuffix);
			contactName.put("formatted", formatted);
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return contactName;
	}

	/**
	 * Create a ContactField JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactField
	 */
	private JSONObject phoneQuery(Cursor cursor) {
		JSONObject phoneNumber = new JSONObject();
		try {
			phoneNumber.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)));
			phoneNumber.put("pref", false); // Android does not store pref attribute
			phoneNumber.put("value", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)));
			phoneNumber.put("type", getPhoneType(cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE))));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		catch (Exception excp) {
			Log.e(LOG_TAG, excp.getMessage(), excp);
		} 
		return phoneNumber;
	}

	/**
	 * Create a ContactField JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactField
	 */
	private JSONObject emailQuery(Cursor cursor) {
		JSONObject email = new JSONObject();
		try {
			email.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email._ID)));
			email.put("pref", false); // Android does not store pref attribute
			email.put("value", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)));
			email.put("type", getContactType(cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE))));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return email;
	}

	/**
	 * Create a ContactField JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactField
	 */
	private JSONObject imQuery(Cursor cursor) {
		JSONObject im = new JSONObject();
		try {
			im.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im._ID)));
			im.put("pref", false); // Android does not store pref attribute
			im.put("value", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.DATA)));
			im.put("type", getContactType(cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.TYPE))));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return im;
	}	

	/**
	 * Create a ContactField JSONObject
	 * @param cursor the current database row
	 * @return a JSONObject representing a ContactField
	 */
	private JSONObject websiteQuery(Cursor cursor) {
		JSONObject website = new JSONObject();
		try {
			website.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Website._ID)));
			website.put("pref", false); // Android does not store pref attribute
			website.put("value", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Website.URL)));
			website.put("type", getContactType(cursor.getInt(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Website.TYPE))));
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return website;
	}	

    private boolean hasContactPhoto(android.content.ContentResolver cr, Uri photoUri) {
            Cursor cursor = cr.query(photoUri, new String[]{ContactsContract.CommonDataKinds.Photo.PHOTO}, null, null, null);
            try {
                if (cursor == null || !cursor.moveToNext()) {
                    return false;
                }
                byte[] data = cursor.getBlob(0);
                if (data == null) {
                    return false;
                }
                return true;
            } finally {
                if (cursor != null) cursor.close();
            }
    }

	/**
	 * Create a ContactField JSONObject
	 * @param contactId 
	 * @return a JSONObject representing a ContactField
	 */
	private JSONObject photoQuery(Cursor cursor, String contactId) {
		JSONObject photo = null;
		try {
		    Uri person = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, (new Long(contactId)));
		    Uri photoUri = Uri.withAppendedPath(person, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY);
            //Cursor cur = mApp.getContentResolver().query(ContactsContract.Data.CONTENT_URI, null , ContactsContract.Data.CONTACT_ID + "=" + contactId + " AND " + ContactsContract.Data.MIMETYPE + "='" + ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE + "'", null, null);
            //InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(mApp.getContentResolver(), person);
			if (hasContactPhoto( mApp.getContentResolver(), photoUri)) {
				photo = new JSONObject();
				photo.put("id", cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Photo._ID)));
				photo.put("pref", false);
				photo.put("type", "url");
				photo.put("value", photoUri.toString());
			}
		} catch (JSONException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return photo;
	}

	@Override
	/**
	 * This method will save a contact object into the devices contacts database.
	 * 
	 * @param contact the contact to be saved.
	 * @returns true if the contact is successfully saved, false otherwise.
	 */
	public boolean save(JSONObject contact) {
		AccountManager mgr = AccountManager.get(mApp);
		Account[] accounts = mgr.getAccounts();
		Account account = null;

		if (accounts.length == 1)
			account = accounts[0];
		else if (accounts.length > 1) {
			for(Account a : accounts){
				if(a.type.contains("eas")&& a.name.matches(EMAIL_REGEXP)) /*Exchange ActiveSync*/
				{
					account = a;
					break;
				}
			}	
			if(account == null){
				for(Account a : accounts){
					if(a.type.contains("com.google") && a.name.matches(EMAIL_REGEXP)) /*Google sync provider*/
					{
						account = a;
						break;
					}
				}
			}
			if(account == null){
				for(Account a : accounts){
					if(a.name.matches(EMAIL_REGEXP)) /*Last resort, just look for an email address...*/
					{
						account = a;
						break;
					}
				}	
			}
		}
		
		if(account == null)
			return false;

		String id = getJsonString(contact, "id");
		// Create new contact
		if (id == null) {
			return createNewContact(contact, account);
		}
		// Modify existing contact
		else {
			return modifyContact(id, contact, account);
		}
	}

	/**
	 * Creates a new contact and stores it in the database
	 * 
	 * @param id the raw contact id which is required for linking items to the contact
	 * @param contact the contact to be saved
	 * @param account the account to be saved under
	 */
	private boolean modifyContact(String id, JSONObject contact, Account account) {
		// Get the RAW_CONTACT_ID which is needed to insert new values in an already existing contact.
		// But not needed to update existing values.
		int rawId = (new Integer(getJsonString(contact,"rawId"))).intValue();
		
		// Create a list of attributes to add to the contact database
		ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
		
		//Add contact type
		ops.add(ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
		        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
		        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
		        .build());
		
		// Modify name
		JSONObject name;
		try {
			String displayName = getJsonString(contact, "displayName");
			name = contact.getJSONObject("name");
			if (displayName != null || name != null) {
				ContentProviderOperation.Builder builder = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
					.withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + 
							ContactsContract.Data.MIMETYPE + "=?", 
							new String[]{id, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE});

				if (displayName != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName);
				}
					
				String familyName = getJsonString(name, "familyName");
				if (familyName != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName);
				}
				String middleName = getJsonString(name, "middleName");
				if (middleName != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middleName);
				}
				String givenName = getJsonString(name, "givenName");
				if (givenName != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName);
				}
				String honorificPrefix = getJsonString(name, "honorificPrefix");
				if (honorificPrefix != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, honorificPrefix);
				}
				String honorificSuffix = getJsonString(name, "honorificSuffix");
				if (honorificSuffix != null) {
					builder.withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, honorificSuffix);
				}
				
				ops.add(builder.build());
			}
		} catch (JSONException e1) {
			Log.d(LOG_TAG, "Could not get name");
		}
		
		// Modify phone numbers
		JSONArray phones = null;
		try {
			phones = contact.getJSONArray("phoneNumbers");
			if (phones != null) {
				for (int i=0; i<phones.length(); i++) {
					JSONObject phone = (JSONObject)phones.get(i);
					String phoneId = getJsonString(phone, "id");
					// This is a new phone so do a DB insert
					if (phoneId == null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"));
					    contentValues.put(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing phone so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Phone._ID + "=? AND " + 
									ContactsContract.Data.MIMETYPE + "=?", 
									new String[]{phoneId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"))
						        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get phone numbers");
		}
		
		// Modify emails
		JSONArray emails = null;
		try {
			emails = contact.getJSONArray("emails");
			if (emails != null) {
				for (int i=0; i<emails.length(); i++) {
					JSONObject email = (JSONObject)emails.get(i);
					String emailId = getJsonString(email, "id");
					// This is a new email so do a DB insert
					if (emailId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"));
					    contentValues.put(ContactsContract.CommonDataKinds.Email.TYPE, getContactType(getJsonString(email, "type")));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing email so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Email._ID + "=? AND " + 
									ContactsContract.Data.MIMETYPE + "=?", 
									new String[]{emailId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"))
						        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, getContactType(getJsonString(email, "type")))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get emails");
		}

		// Modify addresses
		JSONArray addresses = null;
		try {
			addresses = contact.getJSONArray("addresses");
			if (addresses != null) {
				for (int i=0; i<addresses.length(); i++) {
					JSONObject address = (JSONObject)addresses.get(i);
					String addressId = getJsonString(address, "id");
					// This is a new address so do a DB insert
					if (addressId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, getJsonString(address, "formatted"));
				        contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.STREET, getJsonString(address, "streetAddress"));
				        contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.CITY, getJsonString(address, "locality"));
				        contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.REGION, getJsonString(address, "region"));
				        contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, getJsonString(address, "postalCode"));
				        contentValues.put(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, getJsonString(address, "country"));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing address so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.StructuredPostal._ID + "=? AND " + 
										ContactsContract.Data.MIMETYPE + "=?", 
										new String[]{addressId, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, getJsonString(address, "formatted"))
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, getJsonString(address, "streetAddress"))
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, getJsonString(address, "locality"))
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, getJsonString(address, "region"))
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, getJsonString(address, "postalCode"))
						        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, getJsonString(address, "country"))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get addresses");
		}

		// Modify organizations
		JSONArray organizations = null;
		try {
			organizations = contact.getJSONArray("organizations");
			if (organizations != null) {
				for (int i=0; i<organizations.length(); i++) {
					JSONObject org = (JSONObject)organizations.get(i);
					String orgId = getJsonString(org, "id");;
					// This is a new organization so do a DB insert
					if (orgId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, getJsonString(org, "department"));
				        contentValues.put(ContactsContract.CommonDataKinds.Organization.COMPANY, getJsonString(org, "name"));
				        contentValues.put(ContactsContract.CommonDataKinds.Organization.TITLE, getJsonString(org, "title"));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing organization so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Organization._ID + "=? AND " + 
										ContactsContract.Data.MIMETYPE + "=?", 
										new String[]{orgId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, getJsonString(org, "department"))
						        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, getJsonString(org, "name"))
						        .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, getJsonString(org, "title"))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get organizations");
		}

		// Modify IMs
		JSONArray ims = null;
		try {
			ims = contact.getJSONArray("ims");
			if (ims != null) {
				for (int i=0; i<ims.length(); i++) {
					JSONObject im = (JSONObject)ims.get(i);
					String imId = getJsonString(im, "id");;
					// This is a new IM so do a DB insert
					if (imId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.Im.DATA, getJsonString(im, "value"));
				        contentValues.put(ContactsContract.CommonDataKinds.Im.TYPE, getContactType(getJsonString(im, "type")));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing IM so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Im._ID + "=? AND " + 
										ContactsContract.Data.MIMETYPE + "=?", 
										new String[]{imId, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.Im.DATA, getJsonString(im, "value"))
						        .withValue(ContactsContract.CommonDataKinds.Im.TYPE, getContactType(getJsonString(im, "type")))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get emails");
		}

		// Modify note
		String note = getJsonString(contact, "note");
		ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
				.withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + 
						ContactsContract.Data.MIMETYPE + "=?", 
						new String[]{id,ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE})
		        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
				.build());

		// Modify nickname
		String nickname = getJsonString(contact, "nickname");
		if (nickname != null) {
			ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
					.withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + 
							ContactsContract.Data.MIMETYPE + "=?", 
							new String[]{id,ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE})
			        .withValue(ContactsContract.CommonDataKinds.Nickname.NAME, nickname)
			        .build());
		}
			
		// Modify urls	
		JSONArray websites = null;
		try {
			websites = contact.getJSONArray("websites");
			if (websites != null) {
				for (int i=0; i<websites.length(); i++) {
					JSONObject website = (JSONObject)websites.get(i);
					String websiteId = getJsonString(website, "id");;
					// This is a new website so do a DB insert
					if (websiteId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.CommonDataKinds.Website.DATA, getJsonString(website, "value"));
				        contentValues.put(ContactsContract.CommonDataKinds.Website.TYPE, getContactType(getJsonString(website, "type")));

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing website so do a DB update
					else {
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Website._ID + "=? AND " + 
										ContactsContract.Data.MIMETYPE + "=?", 
										new String[]{websiteId, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.CommonDataKinds.Website.DATA, getJsonString(website, "value"))
						        .withValue(ContactsContract.CommonDataKinds.Website.TYPE, getContactType(getJsonString(website, "type")))
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get websites");
		}
		
		// Modify birthday
		String birthday = getJsonString(contact, "birthday");
		if (birthday != null) {
			ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
					.withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + 
							ContactsContract.Data.MIMETYPE + "=? AND " + 
							ContactsContract.CommonDataKinds.Event.TYPE + "=?", 
							new String[]{id,ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, new String(""+ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)})
			        .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
			        .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, birthday)
			        .build());
		}

		// Modify photos
		JSONArray photos = null;
		try {
			photos = contact.getJSONArray("photos");
			if (photos != null) {
				for (int i=0; i<photos.length(); i++) {
					JSONObject photo = (JSONObject)photos.get(i);
					String photoId = getJsonString(photo, "id");
					byte[] bytes = getPhotoBytes(getJsonString(photo, "value"));
					// This is a new photo so do a DB insert
					if (photoId==null) {
						ContentValues contentValues = new ContentValues();
					    contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
					    contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
					    contentValues.put(ContactsContract.Data.IS_SUPER_PRIMARY, 1);
				        contentValues.put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes);

					    ops.add(ContentProviderOperation.newInsert(
					            ContactsContract.Data.CONTENT_URI).withValues(contentValues).build()); 						
					}
					// This is an existing photo so do a DB update
					else {						
						ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
						        .withSelection(ContactsContract.CommonDataKinds.Photo._ID + "=? AND " + 
										ContactsContract.Data.MIMETYPE + "=?", 
										new String[]{photoId, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE})
						        .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
						        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
						        .build());
					}
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get photos");
		}
		
		boolean retVal = true;
		
		//Modify contact
		try {
			mApp.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
		} catch (RemoteException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
			Log.e(LOG_TAG, Log.getStackTraceString(e), e);
			retVal = false;
		} catch (OperationApplicationException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
			Log.e(LOG_TAG, Log.getStackTraceString(e), e);
			retVal = false;
		}
		
		return retVal;
	}

	/**
	 * Add a website to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param website the item to be inserted
	 */
	private void insertWebsite(ArrayList<ContentProviderOperation> ops,
			JSONObject website) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Website.DATA, getJsonString(website, "value"))
		        .withValue(ContactsContract.CommonDataKinds.Website.TYPE, getContactType(getJsonString(website, "type")))
		        .build());
	}

	/**
	 * Add an im to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param im the item to be inserted
	 */
	private void insertIm(ArrayList<ContentProviderOperation> ops, JSONObject im) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Im.DATA, getJsonString(im, "value"))
		        .withValue(ContactsContract.CommonDataKinds.Im.TYPE, getContactType(getJsonString(im, "type")))
		        .build());
	}

	/**
	 * Add an organization to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param org the item to be inserted
	 */
	private void insertOrganization(ArrayList<ContentProviderOperation> ops,
			JSONObject org) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, getJsonString(org, "department"))
		        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, getJsonString(org, "name"))
		        .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, getJsonString(org, "title"))
		        .build());
	}

	/**
	 * Add an address to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param address the item to be inserted
	 */
	private void insertAddress(ArrayList<ContentProviderOperation> ops,
			JSONObject address) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, getJsonString(address, "formatted"))
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, getJsonString(address, "streetAddress"))
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, getJsonString(address, "locality"))
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, getJsonString(address, "region"))
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, getJsonString(address, "postalCode"))
		        .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, getJsonString(address, "country"))
		        .build());
	}

	/**
	 * Add an email to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param email the item to be inserted
	 */
	private void insertEmail(ArrayList<ContentProviderOperation> ops,
			JSONObject email) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"))
		        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, getPhoneType(getJsonString(email, "type")))
		        .build());
	}

	/**
	 * Add a phone to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param phone the item to be inserted
	 */
	private void insertPhone(ArrayList<ContentProviderOperation> ops,
			JSONObject phone) {
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"))
		        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")))
		        .build());
	}

	/**
	 * Add a phone to a list of database actions to be performed
	 * 
	 * @param ops the list of database actions
	 * @param phone the item to be inserted
	 */
	private void insertPhoto(ArrayList<ContentProviderOperation> ops,
			JSONObject photo) {
		byte[] bytes = getPhotoBytes(getJsonString(photo, "value"));
		ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
		        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
		        .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
		        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
		        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
		        .build());
	}
	
	/**
	 * Gets the raw bytes from the supplied filename
	 * 
	 * @param filename the file to read the bytes from
	 * @return a byte array
	 * @throws IOException 
	 */
	private byte[] getPhotoBytes(String filename) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			int bytesRead = 0;
			long totalBytesRead = 0;
			byte[] data = new byte[8192];
			InputStream in = getPathFromUri(filename);
			
			while ((bytesRead = in.read(data, 0, data.length)) != -1 && totalBytesRead <= MAX_PHOTO_SIZE) {
				buffer.write(data, 0, bytesRead);
				totalBytesRead += bytesRead;
			}
			
			in.close();
			buffer.flush();
		} catch (FileNotFoundException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		} catch (IOException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
		}
		return buffer.toByteArray();
	}
	/**
     * Get an input stream based on file path or uri content://, http://, file://
     * 
     * @param path
     * @return an input stream
	 * @throws IOException 
     */
    private InputStream getPathFromUri(String path) throws IOException {  	  
    	if (path.startsWith("content:")) {
    		Uri uri = Uri.parse(path);
    		return mApp.getContentResolver().openInputStream(uri);
    	}
    	if (path.startsWith("http:") || path.startsWith("file:")) {
    		URL url = new URL(path);
    		return url.openStream();
    	}
    	else {
    		return new FileInputStream(path);
    	}
    }  

	/**
	 * Creates a new contact and stores it in the database
	 * 
	 * @param contact the contact to be saved
	 * @param account the account to be saved under
	 */
	private boolean createNewContact(JSONObject contact, Account account) {
		// Create a list of attributes to add to the contact database
		ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

		//Add contact type
		ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
		        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
		        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
		        .build());

		// Add name
		try {
			JSONObject name = contact.optJSONObject("name");
			String displayName = contact.getString("displayName");
			if (displayName != null || name != null) {
				ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
						.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
						.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
						.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
						.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, getJsonString(name, "familyName"))
						.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, getJsonString(name, "middleName"))
						.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, getJsonString(name, "givenName"))
						.withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, getJsonString(name, "honorificPrefix"))
						.withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, getJsonString(name, "honorificSuffix"))
						.build());
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get name object");
		}
		
		//Add phone numbers
		JSONArray phones = null;
		try {
			phones = contact.getJSONArray("phoneNumbers");
			if (phones != null) {
				for (int i=0; i<phones.length(); i++) {
					JSONObject phone = (JSONObject)phones.get(i);
					insertPhone(ops, phone);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get phone numbers");
		}
				
		// Add emails
		JSONArray emails = null;
		try {
			emails = contact.getJSONArray("emails");
			if (emails != null) {
				for (int i=0; i<emails.length(); i++) {
					JSONObject email = (JSONObject)emails.get(i);
					insertEmail(ops, email);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get emails");
		}

		// Add addresses
		JSONArray addresses = null;
		try {
			addresses = contact.getJSONArray("addresses");
			if (addresses != null) {
				for (int i=0; i<addresses.length(); i++) {
					JSONObject address = (JSONObject)addresses.get(i);
					insertAddress(ops, address);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get addresses");
		}

		// Add organizations
		JSONArray organizations = null;
		try {
			organizations = contact.getJSONArray("organizations");
			if (organizations != null) {
				for (int i=0; i<organizations.length(); i++) {
					JSONObject org = (JSONObject)organizations.get(i);
					insertOrganization(ops, org);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get organizations");
		}

		// Add IMs
		JSONArray ims = null;
		try {
			ims = contact.getJSONArray("ims");
			if (ims != null) {
				for (int i=0; i<ims.length(); i++) {
					JSONObject im = (JSONObject)ims.get(i);
					insertIm(ops, im);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get emails");
		}

		// Add note
		String note = getJsonString(contact, "note");
		if (note != null) {
			ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
			        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
			        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
			        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
			        .build());
		}

		// Add nickname
		String nickname = getJsonString(contact, "nickname");
		if (nickname != null) {
			ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
			        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
			        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
			        .withValue(ContactsContract.CommonDataKinds.Nickname.NAME, nickname)
			        .build());
		}
		
		
		// Add urls	
		JSONArray websites = null;
		try {
			websites = contact.getJSONArray("websites");
			if (websites != null) {
				for (int i=0; i<websites.length(); i++) {
					JSONObject website = (JSONObject)websites.get(i);
					insertWebsite(ops, website);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get websites");
		}
		
		// Add birthday
		String birthday = getJsonString(contact, "birthday");
		if (birthday != null) {
			ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
			        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
			        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
			        .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
			        .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, birthday)
			        .build());
		}
		
		// Add photos
		JSONArray photos = null;
		try {
			photos = contact.getJSONArray("photos");
			if (photos != null) {
				for (int i=0; i<photos.length(); i++) {
					JSONObject photo = (JSONObject)photos.get(i);
					insertPhoto(ops, photo);
				}
			}
		}
		catch (JSONException e) {
			Log.d(LOG_TAG, "Could not get photos");
		}

		boolean retVal = true;
		//Add contact
		try {
			mApp.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
		} catch (RemoteException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
			retVal = false;
		} catch (OperationApplicationException e) {
			Log.e(LOG_TAG, e.getMessage(), e);
			retVal = false;
		}
		
		return retVal;
	}

	@Override
	/** 
	 * This method will remove a Contact from the database based on ID.
	 * @param id the unique ID of the contact to remove
	 */
	public boolean remove(String id) {
    	int result = mApp.getContentResolver().delete(ContactsContract.Data.CONTENT_URI, 
    			ContactsContract.Data.CONTACT_ID + " = ?", 
    			new String[] {id});    	
    	return (result > 0) ? true : false;
	}	

/**************************************************************************
 * 	
 * All methods below this comment are used to convert from JavaScript 
 * text types to Android integer types and vice versa.
 * 
 *************************************************************************/
	
	/**
	 * Converts a string from the W3C Contact API to it's Android int value.
	 * @param string
	 * @return Android int value
	 */
	private int getPhoneType(String string) {
		int type = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;
		if ("home".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_HOME;
		}
		else if ("mobile".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
		}
		else if ("work".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_WORK;
		}
		else if ("work fax".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK;
		}
		else if ("home fax".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME;
		}
		else if ("fax".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK;
		}
		else if ("pager".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_PAGER;
		}
		else if ("other".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;
		}
		else if ("car".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_CAR;
		}
		else if ("company main".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN;
		}
		else if ("isdn".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_ISDN;
		}
		else if ("main".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_MAIN;
		}
		else if ("other fax".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX;
		}
		else if ("radio".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_RADIO;
		}
		else if ("telex".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_TELEX;
		}
		else if ("work mobile".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE;
		}
		else if ("work pager".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER;
		}
		else if ("assistant".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT;
		}
		else if ("mms".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_MMS;
		}
		else if ("callback".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK;
		}
		else if ("tty ttd".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD;
		}
		else if ("custom".equals(string.toLowerCase())) {
			return ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM;
		}
		return type;
	}

	/**
	 * getPhoneType converts an Android phone type into a string
	 * @param type 
	 * @return phone type as string.
	 */
	private String getPhoneType(int type) {
		String stringType;
		switch (type) {
		case ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM:
			stringType = "custom";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
			stringType = "home fax";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
			stringType = "work fax";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
			stringType = "home";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
			stringType = "mobile";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:
			stringType = "pager";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
			stringType = "work";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK:
			stringType = "callback";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_CAR:
			stringType = "car";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN:
			stringType = "company main";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX:
			stringType = "other fax";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_RADIO:
			stringType = "radio";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_TELEX:
			stringType = "telex";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_TTY_TDD:
			stringType = "tty tdd";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE:
			stringType = "work mobile";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER:
			stringType = "work pager";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_ASSISTANT:
			stringType = "assistant";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_MMS:
			stringType = "mms";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_ISDN:
			stringType = "isdn";
			break;
		case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
		default: 
			stringType = "other";
			break;
		}
		return stringType;
	}

	/**
	 * Converts a string from the W3C Contact API to it's Android int value.
	 * @param string
	 * @return Android int value
	 */
	private int getContactType(String string) {
		int type = ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
		if (string!=null) {
			if ("home".equals(string.toLowerCase())) {
				return ContactsContract.CommonDataKinds.Email.TYPE_HOME;
			}
			else if ("work".equals(string.toLowerCase())) {
				return ContactsContract.CommonDataKinds.Email.TYPE_WORK;
			}
			else if ("other".equals(string.toLowerCase())) {
				return ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
			}
			else if ("mobile".equals(string.toLowerCase())) {
				return ContactsContract.CommonDataKinds.Email.TYPE_MOBILE;
			}
			else if ("custom".equals(string.toLowerCase())) {
				return ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM;
			}		
		}
		return type;
	}

	/**
	 * getPhoneType converts an Android phone type into a string
	 * @param type 
	 * @return phone type as string.
	 */
	private String getContactType(int type) {
		String stringType;
		switch (type) {
			case ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM: 
				stringType = "custom";
				break;
			case ContactsContract.CommonDataKinds.Email.TYPE_HOME: 
				stringType = "home";
				break;
			case ContactsContract.CommonDataKinds.Email.TYPE_WORK: 
				stringType = "work";
				break;
			case ContactsContract.CommonDataKinds.Email.TYPE_MOBILE: 
				stringType = "mobile";
				break;
			case ContactsContract.CommonDataKinds.Email.TYPE_OTHER: 
			default: 
				stringType = "other";
				break;
		}
		return stringType;
	}
}
