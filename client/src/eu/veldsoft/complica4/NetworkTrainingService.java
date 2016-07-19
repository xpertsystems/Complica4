package eu.veldsoft.complica4;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.data.basic.BasicMLDataSet;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.WakefulBroadcastReceiver;
import eu.veldsoft.complica4.model.Board;
import eu.veldsoft.complica4.model.Example;
import eu.veldsoft.complica4.model.Piece;
import eu.veldsoft.complica4.model.Util;
import eu.veldsoft.complica4.storage.MovesHistoryDatabaseHelper;

/**
 * Artificial Neural Network training class.
 * 
 * @author Todor Balabanov
 */
public class NetworkTrainingService extends Service {
	/**
	 * Database helper object.
	 */
	private MovesHistoryDatabaseHelper helper = null;

	/**
	 * Common object between training thread and HTTP communication tread.
	 */
	private BasicNetwork storeOnRemote = null;

	/**
	 * Keep track of ANN error.
	 */
	private double annTrainingError = Double.MAX_VALUE;

	/**
	 * Setup alarm for service activation.
	 */
	private void setupAlarm() {
		/*
		 * Do not set if it is already there.
		 */
		if (PendingIntent.getBroadcast(this, Util.ALARM_REQUEST_CODE,
				new Intent(getApplicationContext(),
						NetworkTrainingService.class),
				PendingIntent.FLAG_NO_CREATE) != null) {
			return;
		}

		/*
		 * Parameterize weak-up interval.
		 */
		long interval = AlarmManager.INTERVAL_HALF_HOUR;
		try {
			interval = getPackageManager().getServiceInfo(
					new ComponentName(NetworkTrainingService.this,
							NetworkTrainingService.this.getClass()),
					PackageManager.GET_SERVICES | PackageManager.GET_META_DATA).metaData
					.getInt("interval", (int) AlarmManager.INTERVAL_HALF_HOUR);
		} catch (NameNotFoundException exception) {
			interval = AlarmManager.INTERVAL_HALF_HOUR;
			System.err.println(exception);
		}

		((AlarmManager) this.getSystemService(Context.ALARM_SERVICE))
				.setInexactRepeating(AlarmManager.RTC_WAKEUP, System
						.currentTimeMillis(), interval, PendingIntent
						.getBroadcast(this, Util.ALARM_REQUEST_CODE,
								new Intent(getApplicationContext(),
										NetworkTrainingReceiver.class),
								PendingIntent.FLAG_UPDATE_CURRENT));
	}

	/**
	 * Connect to the remote server and check the best known error.
	 * 
	 * @return The best remote error if such was found or max double otherwise.
	 */
	private double obtainRemoveBestError() {
		String host = "";
		try {
			host = getPackageManager().getApplicationInfo(
					NetworkTrainingService.this.getPackageName(),
					PackageManager.GET_META_DATA).metaData.getString("host");
		} catch (NameNotFoundException exception) {
			System.err.println(exception);
			return Double.MAX_VALUE;
		}

		String script = "";
		try {
			script = getPackageManager().getServiceInfo(
					new ComponentName(NetworkTrainingService.this,
							NetworkTrainingService.this.getClass()),
					PackageManager.GET_SERVICES | PackageManager.GET_META_DATA).metaData
					.getString("best_rating_script");
		} catch (NameNotFoundException exception) {
			System.err.println(exception);
			return Double.MAX_VALUE;
		}

		HttpClient client = new DefaultHttpClient();
		client.getParams().setParameter("http.protocol.content-charset",
				"UTF-8");
		HttpPost post = new HttpPost("http://" + host + "/" + script);

		JSONObject json = new JSONObject();
		List<NameValuePair> pairs = new ArrayList<NameValuePair>();
		pairs.add(new BasicNameValuePair("best_rating", json.toString()));
		try {
			post.setEntity(new UrlEncodedFormEntity(pairs));
		} catch (UnsupportedEncodingException exception) {
			System.err.println(exception);
		}

		double error = Double.MAX_VALUE;
		try {
			HttpResponse response = client.execute(post);
			JSONObject result = new JSONObject(EntityUtils.toString(
					response.getEntity(), "UTF-8"));
			error = result.getDouble(Util.JSON_RATING_KEY);
		} catch (ClientProtocolException exception) {
			System.err.println(exception);
		} catch (IOException exception) {
			System.err.println(exception);
		} catch (ParseException exception) {
			System.err.println(exception);
		} catch (JSONException exception) {
			System.err.println(exception);
		}

		return error;
	}

	/**
	 * Service constructor.
	 */
	public NetworkTrainingService() {
		super();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onCreate() {
		super.onCreate();

		if (helper == null) {
			helper = new MovesHistoryDatabaseHelper(NetworkTrainingService.this);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int id) {
		/*
		 * Check alarm.
		 */
		setupAlarm();

		/*
		 * Release wake-up lock.
		 */
		if (intent.getAction() == Intent.ACTION_BOOT_COMPLETED) {
			WakefulBroadcastReceiver.completeWakefulIntent(intent);
		}

		/*
		 * Single training cycle.
		 */
		(new AsyncTask<Void, Void, Void>() {
			/**
			 * Network training.
			 */
			@Override
			protected Void doInBackground(Void... params) {
				/*
				 * Load network from a file.
				 */
				BasicNetwork net = Util.loadFromFile(getFilesDir() + "/"
						+ Util.ANN_FILE_NAME);

				/*
				 * Create new network if there is no network in the file.
				 */
				if (net == null) {
					net = Util.newNetwork(Board.COLS * Board.ROWS
							+ Board.NUMBER_OF_PLAYERS, Board.COLS * Board.ROWS
							/ 2, Board.COLS);
				}

				/*
				 * Form training set.
				 */
				double min = Piece.getMinId();
				double max = Piece.getMaxId();
				double inputSet[][] = new double[Util.NUMBER_OF_SINGLE_TRAINING_EXAMPLES][net
						.getInputCount()];
				double expectedSet[][] = new double[Util.NUMBER_OF_SINGLE_TRAINING_EXAMPLES][net
						.getOutputCount()];

				/*
				 * If there is no training examples do nothing.
				 */
				if (helper == null || helper.hasMove() == false) {
					return null;
				}

				/*
				 * Fill training examples.
				 */
				for (int e = 0; e < Util.NUMBER_OF_SINGLE_TRAINING_EXAMPLES; e++) {
					Example example = helper.retrieveMove();

					/*
					 * Scale input in the range of [0.0-1.0].
					 */
					double input[] = new double[net.getInputCount()];
					for (int i = 0, k = 0; i < example.state.length; i++) {
						for (int j = 0; j < example.state[i].length; j++, k++) {
							input[k] = (example.state[i][j] - min)
									/ (max - min);
						}
					}

					/*
					 * Mark the player who is playing.
					 */
					for (int i = input.length - Board.NUMBER_OF_PLAYERS, p = 1; i < input.length; i++, p++) {
						if (example.piece == p) {
							input[i] = 1;
						} else {
							input[i] = 0;
						}
					}

					/*
					 * Mark the column to playing.
					 */
					double expected[] = new double[net.getOutputCount()];
					for (int i = 0; i < expected.length; i++) {
						if (example.colunm == i) {
							expected[i] = 1;
						} else {
							expected[i] = 0;
						}
					}

					/*
					 * For training pair.
					 */
					inputSet[e] = input;
					expectedSet[e] = expected;
				}

				/*
				 * Build training data set.
				 */
				MLDataSet trainingSet = new BasicMLDataSet(inputSet,
						expectedSet);

				/*
				 * a Train network.
				 */
				ResilientPropagation train = new ResilientPropagation(net,
						trainingSet);
				train.iteration();
				train.finishTraining();

				/*
				 * Save network to a file.
				 */
				Util.saveToFile(net, getFilesDir() + "/" + Util.ANN_FILE_NAME);
				annTrainingError = net.calculateError(trainingSet);
				storeOnRemote = net;

				/*
				 * Stop service.
				 */
				NetworkTrainingService.this.stopSelf();
				return null;
			}
		}).execute();

		/*
		 * Save network to the remote server.
		 */
		(new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				/*
				 * Nothing to report.
				 */
				while (storeOnRemote == null) {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
					}
				}

				/*
				 * Do not report if local ANN is worse than the best remote ANN.
				 */
				if (annTrainingError > obtainRemoveBestError()) {
					storeOnRemote = null;
					return null;
				}

				String host = "";
				try {
					host = getPackageManager().getApplicationInfo(
							NetworkTrainingService.this.getPackageName(),
							PackageManager.GET_META_DATA).metaData
							.getString("host");
				} catch (NameNotFoundException exception) {
					System.err.println(exception);
					return null;
				}

				String script = "";
				try {
					script = getPackageManager().getServiceInfo(
							new ComponentName(NetworkTrainingService.this,
									NetworkTrainingService.this.getClass()),
							PackageManager.GET_SERVICES
									| PackageManager.GET_META_DATA).metaData
							.getString("save_neural_network_script");
				} catch (NameNotFoundException exception) {
					System.err.println(exception);
					return null;
				}

				HttpClient client = new DefaultHttpClient();
				client.getParams().setParameter(
						"http.protocol.content-charset", "UTF-8");
				HttpPost post = new HttpPost("http://" + host + "/" + script);

				JSONObject json = new JSONObject();
				try {
					json.put(Util.JSON_OBJECT_KEY, storeOnRemote);
					json.put(Util.JSON_RATING_KEY, annTrainingError);
				} catch (JSONException exception) {
					System.err.println(exception);
				}

				List<NameValuePair> pairs = new ArrayList<NameValuePair>();
				pairs.add(new BasicNameValuePair("save_neural_network", json
						.toString()));
				try {
					post.setEntity(new UrlEncodedFormEntity(pairs));
				} catch (UnsupportedEncodingException exception) {
					System.err.println(exception);
				}

				try {
					HttpResponse response = client.execute(post);
				} catch (ClientProtocolException exception) {
					System.err.println(exception);
				} catch (IOException exception) {
					System.err.println(exception);
				}

				return null;
			}
		}).execute();

		return START_NOT_STICKY;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onDestroy() {
		/*
		 * Close SQLite database connection.
		 */
		if (helper != null) {
			helper.close();
			helper = null;
		}

		super.onDestroy();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return new Binder() {
			Service getService() {
				return NetworkTrainingService.this;
			}
		};
	}
}
