from flask import Flask, request, jsonify
import pandas as pd
from prophet import Prophet
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)

app = Flask(__name__)

def run_forecast(json_data):
    """Generic forecast function to handle Prophet logic."""
    if not json_data or 'data' not in json_data or 'periods' not in json_data:
        logging.error("Invalid request body: 'data' and 'periods' are required.")
        raise ValueError("Invalid request body: 'data' and 'periods' are required.")

    history_df = pd.DataFrame(json_data['data'])
    periods_to_forecast = int(json_data['periods'])

    # Validate dataframe columns
    if not all(col in history_df.columns for col in ['ds', 'y']):
        logging.error("Input data to run_forecast is missing 'ds' or 'y' columns.")
        raise ValueError("Data must contain 'ds' and 'y' columns.")

    # --- CRITICAL FIX: Specify the expected date format ---
    # The Java backend should be sending dates in 'YYYY-MM-DD' format (ISO 8601).
    # Telling pandas to expect this format will prevent the 'UserWarning'
    # and the 'Out of bounds nanosecond timestamp' error.
    try:
        history_df['ds'] = pd.to_datetime(history_df['ds'], format='%Y-%m-%d')
    except Exception as e:
        logging.error(f"Error parsing 'ds' column to datetime. Ensure dates are in YYYY-MM-DD format: {e}")
        raise ValueError(f"Date parsing error in 'ds' column: {e}. Please ensure YYYY-MM-DD format.")

    history_df['y'] = pd.to_numeric(history_df['y'])

    # Drop rows with NaT in 'ds' or NaN in 'y' that might result from parsing errors
    initial_len = len(history_df)
    history_df.dropna(subset=['ds', 'y'], inplace=True)
    if len(history_df) < initial_len:
        logging.warning(f"Removed {initial_len - len(history_df)} rows with invalid 'ds' or 'y' values after parsing.")
        if len(history_df) < 2: # Prophet needs at least 2 data points
             raise ValueError("Not enough valid historical data points after cleaning for forecasting.")


    logging.info(f"Received {len(history_df)} data points. Forecasting for {periods_to_forecast} periods.")

    # Initialize and fit the Prophet model
    # Disabling weekly seasonality for non-cost metrics which may not have it
    weekly_seasonality = json_data.get("weekly_seasonality", True)
    
    # Check if there are enough observations for n_changepoints
    # Default n_changepoints is 25. If data is small, reduce it.
    n_obs = len(history_df)
    if n_obs <= 25:
        # A common heuristic is n_changepoints = (number of observations * 0.8) for small datasets
        # or just ensure it's less than n_obs-1
        n_changepoints_param = min(25, n_obs - 1)
        if n_changepoints_param < 1: # Prophet needs at least 1 changepoint or it can error
            n_changepoints_param = 0 # If not enough data for changepoints, set to 0.
        
        logging.info(f"n_changepoints adjusted to {n_changepoints_param} due to {n_obs} observations.")
        model = Prophet(daily_seasonality=True, weekly_seasonality=weekly_seasonality, n_changepoints=n_changepoints_param)
    else:
        model = Prophet(daily_seasonality=True, weekly_seasonality=weekly_seasonality)


    model.fit(history_df)

    future_df = model.make_future_dataframe(periods=periods_to_forecast)
    forecast_df = model.predict(future_df)
    
    logging.info("Forecast generated successfully.")
    
    return forecast_df[['ds', 'yhat', 'yhat_lower', 'yhat_upper']].to_json(orient='records')


@app.route('/forecast/cost', methods=['POST'])
def forecast_cost():
    """Endpoint specifically for cost forecasting."""
    try:
        return run_forecast(request.get_json())
    except Exception as e:
        logging.error(f"An error occurred during cost forecasting: {e}")
        return jsonify({"error": str(e)}), 500

@app.route('/forecast/performance', methods=['POST'])
def forecast_performance():
    """Endpoint for generic performance metrics."""
    try:
        # Performance metrics often don't have a strong weekly pattern, so we disable it
        json_data = request.get_json()
        
        # Ensure json_data is not None before modifying
        if json_data is None:
            raise ValueError("Request body is empty for performance forecasting.")

        json_data["weekly_seasonality"] = False
        return run_forecast(json_data)
    except Exception as e:
        logging.error(f"An error occurred during performance forecasting: {e}")
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    # Run on port 5001 to avoid conflict with the Java app, using 5002 as per your log
    app.run(debug=True, port=5002)