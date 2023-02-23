const HtmlWebpackPlugin = require('html-webpack-plugin');
const TerserPlugin = require("terser-webpack-plugin");

const config = {
    mode: process.env.WEBPACK_SERVE ? 'development' : 'production',
    entry: {
        demo: './src/demo.js'
    },
    output: {
        filename: '[name].js',
    },
    stats: {warnings: false},
    optimization: {
        minimize: true,
        minimizer: [new TerserPlugin({
            terserOptions: {
                format: {
                    comments: false,
                },
            },
            extractComments: false,
        })]},
    resolve: {
        extensions: ['.tsx', '.ts', '.js'],
        fallback: { crypto: false, path: false, fs: false, util: false }
    },
    devServer: {
        port: 5555,
        static: ['assets'],
        client: {overlay: false}
    },
    module: {
        rules: [
            {
                test: /\.m?js/,
                resolve: {
                    fullySpecified: false,
                },
            },
            {
                test: /\.(woff|woff2|eot|ttf|svg|png)$/,
                type: 'asset/inline',
            }
        ],
    },
    plugins: [
        new HtmlWebpackPlugin({
            chunks: ['demo'],
            template: "./src/demo.html"
        })
    ]
}

module.exports = (env) => {

    console.log(process.env.WEBPACK_SERVE ? 'SERVING DEVELOPMENT ...' : 'BUILDING PRODUCTION ...');

    if (process.env.WEBPACK_SERVE) {
        config.devtool = 'eval-cheap-source-map';
        config.stats = {warnings: false};
    }

    return config;
}
