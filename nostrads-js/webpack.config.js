const path = require('path');

module.exports = [
    // Client bundle for <script>
    {
        entry: { 'nostrads-client': './src/console/nostrads-client.js' },
        output: {
            path: path.resolve(__dirname, 'src'),
            filename: 'nostrads-client.js',
            library: 'NostrAds',
            libraryExport: 'default', // <-- add this line

            libraryTarget: 'umd',
            globalObject: 'this'
        },
        cache: { type: 'filesystem' },
        devtool: false,
        // optimization: {
        //     minimize: false
        // },
        mode: 'production',
        module: {
            rules: [
                {
                    test: /\.js$/,
                    exclude: /node_modules/,
                    use: {
                        loader: 'babel-loader',
                        options: { presets: ['@babel/preset-env'] }
                    }
                }
            ]
        }
    },
    // Worker bundle (plain JS, no UMD wrapper)
    {
        entry: { 'nostrads-worker': './src/console/nostrads-worker.js' },
        output: {
            path: path.resolve(__dirname, 'src'),
            filename: 'nostrads-worker.js'
            // No library, no UMD wrapper
        },
        cache: { type: 'filesystem' },
        devtool: false,
        // optimization: {
        //     minimize: false
        // },
        mode: 'production',
        module: {
            rules: [
                {
                    test: /\.js$/,
                    exclude: /node_modules/,
                    use: {
                        loader: 'babel-loader',
                        options: { presets: ['@babel/preset-env'] }
                    }
                }
            ]
        }
    }
];