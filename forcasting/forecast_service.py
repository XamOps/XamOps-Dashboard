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

    try:
        history_df['ds'] = pd.to_datetime(history_df['ds'], format='%Y-%m-%d')
    except Exception as e:
        logging.error(f"Error parsing 'ds' column to datetime. Ensure dates are in YYYY-MM-DD format: {e}")
        raise ValueError(f"Date parsing error in 'ds' column: {e}. Please ensure YYYY-MM-DD format.")

    history_df['y'] = pd.to_numeric(history_df['y'])

    initial_len = len(history_df)
    history_df.dropna(subset=['ds', 'y'], inplace=True)
    if len(history_df) < initial_len:
        logging.warning(f"Removed {initial_len - len(history_df)} rows with invalid 'ds' or 'y' values after parsing.")
        if len(history_df) < 2: # Prophet needs at least 2 data points
             raise ValueError("Not enough valid historical data points after cleaning for forecasting.")


    logging.info(f"Received {len(history_df)} data points. Forecasting for {periods_to_forecast} periods.")

    weekly_seasonality = json_data.get("weekly_seasonality", True)
    
    n_obs = len(history_df)
    if n_obs <= 25:
        n_changepoints_param = min(25, n_obs - 1)
        if n_changepoints_param < 1:
            n_changepoints_param = 0
        
        logging.info(f"n_changepoints adjusted to {n_changepoints_param} due to {n_obs} observations.")
        model = Prophet(daily_seasonality=True, weekly_seasonality=weekly_seasonality, n_changepoints=n_changepoints_param)
    else:
        model = Prophet(daily_seasonality=True, weekly_seasonality=weekly_seasonality)


    model.fit(history_df)

    future_df = model.make_future_dataframe(periods=periods_to_forecast)
    forecast_df = model.predict(future_df)
    
    # --- FIX: Format the 'ds' column to a simple 'YYYY-MM-DD' string ---
    forecast_df['ds'] = forecast_df['ds'].dt.strftime('%Y-%m-%d')
    
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
        json_data = request.get_json()
        
        if json_data is None:
            raise ValueError("Request body is empty for performance forecasting.")

        json_data["weekly_seasonality"] = False
        return run_forecast(json_data)
    except Exception as e:
        logging.error(f"An error occurred during performance forecasting: {e}")
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    app.run(debug=True, port=5002)